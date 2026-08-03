variable "region" {
  description = "AWS region for the temporary benchmark stack."
  type        = string
  default     = "us-east-1"
}

variable "run_id" {
  description = "Benchmark run id; namespaces every resource so an audit can find leftovers."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9-]{4,40}$", var.run_id))
    error_message = "run_id must be 4-40 lowercase alphanumeric or dash characters."
  }
}

variable "owner" {
  description = "Resource owner tag."
  type        = string
  default     = "amirr-k"
}

variable "commit" {
  description = "Commit SHA the benchmark measures; recorded on every resource and in the evidence."
  type        = string
}

variable "expires_at" {
  description = "ISO-8601 timestamp after which any surviving resource is a leak."
  type        = string
}

variable "operator_cidr" {
  description = "The only CIDR allowed to SSH in. Must be a single address, never 0.0.0.0/0."
  type        = string

  validation {
    condition     = var.operator_cidr != "0.0.0.0/0"
    error_message = "operator_cidr must not be 0.0.0.0/0 — restrict SSH to your own address."
  }
}

variable "ssh_public_key_path" {
  description = "Path to the public key authorized on the instance."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "instance_type" {
  description = "Benchmark host size. Kept small deliberately: the $1.00 one-time ceiling is the binding constraint."
  type        = string
  default     = "t3.medium"
}

variable "volume_size_gb" {
  description = "Root EBS volume size. Deleted with the instance."
  type        = number
  default     = 20
}
