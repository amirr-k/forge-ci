// Temporary AWS environment for one benchmark run. It exists to be destroyed: every resource here
// is either free or billed by the hour, nothing is retained, and `terraform destroy` is the single
// teardown path. This is disposable benchmark scaffolding, not production infrastructure — see
// spec/phases/phase-10-deployment.md for the scope limit that keeps it that way.
//
// Deliberately absent, because each would add recurring cost or complexity for no benchmark value:
// RDS, MSK, ElastiCache, ECS, EKS, NAT Gateway, load balancer, Route 53, allocated Elastic IP,
// remote state, modules, multi-environment wiring.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  // local state on purpose: one ephemeral stack, one operator, destroyed the same hour it is made
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

locals {
  name = "forgeci-bench-${var.run_id}"

  // every resource carries these so the post-teardown audit can find anything left behind
  tags = {
    project    = "forgeci"
    owner      = var.owner
    commit     = var.commit
    run_id     = var.run_id
    expires_at = var.expires_at
    managed_by = "terraform"
    ephemeral  = "true"
  }
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
}

// Artifact store for the run. force_destroy is what makes teardown reliable: a bucket holding
// objects cannot be deleted, and a bucket left behind is exactly the leak this stack must not have.
resource "aws_s3_bucket" "artifacts" {
  bucket        = "${local.name}-artifacts"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

// belt and braces: if teardown is somehow skipped, the objects still age out rather than bill forever
resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    id     = "expire-benchmark-artifacts"
    status = "Enabled"

    filter {}

    expiration {
      days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

// SSH only, only from the operator's address. No application port is public: the benchmark is
// driven over SSH, and nothing here is meant to serve traffic.
resource "aws_security_group" "bench" {
  name        = local.name
  description = "ForgeCI temporary benchmark host"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH from the operator only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.operator_cidr]
  }

  egress {
    description = "pull container images and reach S3"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

// The instance reaches S3 through this role, so the run never needs a long-lived access key.
resource "aws_iam_role" "bench" {
  name = local.name

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "bench_s3" {
  name = "${local.name}-s3"
  role = aws_iam_role.bench.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = aws_s3_bucket.artifacts.arn
      },
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.artifacts.arn}/*"
      }
    ]
  })
}

resource "aws_iam_instance_profile" "bench" {
  name = local.name
  role = aws_iam_role.bench.name
}

resource "aws_key_pair" "bench" {
  key_name   = local.name
  public_key = file(var.ssh_public_key_path)
}

resource "aws_instance" "bench" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.bench.id]
  iam_instance_profile        = aws_iam_instance_profile.bench.name
  key_name                    = aws_key_pair.bench.key_name
  associate_public_ip_address = true

  // auto-assigned, released with the instance — an allocated Elastic IP would keep billing after
  // the instance is gone, which is the classic way a "destroyed" benchmark stack keeps costing money

  root_block_device {
    volume_size           = var.volume_size_gb
    volume_type           = "gp3"
    delete_on_termination = true
    encrypted             = true
  }

  metadata_options {
    http_tokens   = "required" // IMDSv2 only
    http_endpoint = "enabled"
  }

  user_data = <<-EOF
    #!/bin/bash
    set -eux
    dnf update -y
    dnf install -y docker git java-21-amazon-corretto-headless python3
    systemctl enable --now docker
    usermod -aG docker ec2-user
    mkdir -p /usr/local/lib/docker/cli-plugins
    curl -sSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    touch /var/lib/cloud/forge-ready
  EOF

  tags = {
    Name = local.name
  }
}
