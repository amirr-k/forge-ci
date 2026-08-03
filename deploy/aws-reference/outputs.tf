output "public_ip" {
  description = "Public IPv4 of the benchmark host. Auto-assigned, released on destroy."
  value       = aws_instance.bench.public_ip
}

output "instance_id" {
  description = "EC2 instance id, for the post-teardown audit."
  value       = aws_instance.bench.id
}

output "bucket" {
  description = "Artifact bucket name; emptied and deleted on destroy via force_destroy."
  value       = aws_s3_bucket.artifacts.id
}

output "region" {
  value = var.region
}

output "run_id" {
  value = var.run_id
}

output "ssh" {
  description = "Ready-to-paste SSH command."
  value       = "ssh ec2-user@${aws_instance.bench.public_ip}"
}
