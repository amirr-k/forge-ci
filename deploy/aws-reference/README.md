# Temporary AWS reference environment

Stands up the full ForgeCI stack on one EC2 host against **real Amazon S3**, runs the official
benchmark, pulls the evidence back, and destroys everything. It is built to be torn down: nothing
here is retained, and recurring cost after `destroy` is `$0`.

This is disposable benchmark scaffolding. It is not production hosting, and Terraform is not
claimed as a project skill — see the scope limit in
`spec/phases/phase-10-deployment.md`.

## Before you start

```bash
aws sts get-caller-identity          # must succeed; refresh SSO / keys if it does not
export FORGE_RUN_ID=bench-$(date -u +%Y%m%d-%H%M)
export FORGE_OPERATOR_CIDR=$(curl -s https://checkip.amazonaws.com)/32   # optional; auto-detected
```

`FORGE_OPERATOR_CIDR` is the only address allowed to SSH in. `0.0.0.0/0` is rejected by a variable
validation rule, not by convention.

## The whole run

```bash
./deploy/aws-reference/aws-reference run
```

That is: estimate → provision → deploy → smoke → benchmark → evidence → destroy → audit.
**`destroy` and `audit` run even if an earlier step fails or you interrupt it** — that is the
main reason this wrapper exists rather than a list of manual steps.

## Individual commands

| Command | What it does |
|---|---|
| `estimate` | Prints the maximum one-time cost and **exits non-zero if it exceeds $1.00** |
| `inventory` | Lists every `project=forgeci` resource in the region |
| `provision` | Records the starting inventory, refuses a dirty account, then `terraform apply` |
| `deploy` | Ships the repo over SSH and starts the stack pointed at real S3 |
| `smoke` | Health and readiness checks against the deployed control plane |
| `benchmark` | Runs the official suite on the host with `--profile aws-reference` |
| `evidence` | Copies raw results back **before** anything is destroyed |
| `destroy` | `terraform destroy` |
| `audit` | Re-inventories and **fails** if any billable tagged resource survives |

## What it creates

One `t3.medium`, one 20 GB gp3 root volume (`delete_on_termination = true`), one security group
(SSH from your address only), one S3 bucket (`force_destroy = true`, 1-day lifecycle expiry), one
IAM role + instance profile scoped to that single bucket, and one key pair. The public IPv4 is
auto-assigned, never an allocated Elastic IP — an allocated EIP keeps billing after the instance
is gone, which is the usual way a "destroyed" stack keeps costing money.

Deliberately **not** created: RDS, MSK, ElastiCache, ECS, EKS, NAT Gateway, load balancer,
Route 53, Elastic IP, remote Terraform state, or any long-lived access key.

Estimated cost for a 2-hour window: **~$0.10**.

## Why the audit is the real deliverable

Phase 11 treats an inconclusive cleanup audit as a **failed run**. `audit` re-queries EC2
instances, EBS volumes, Elastic IPs, security groups, S3 buckets, IAM roles, and key pairs by tag,
and exits non-zero if anything remains. `provision` refuses to start on an account that already
has tagged ForgeCI resources, because then a leak could not be distinguished from something that
was already there.

Both inventories are written to `benchmarks/results/raw/aws-inventory-{before,after}-$RUN_ID.txt`
and committed as evidence.

## If something goes wrong mid-run

```bash
./deploy/aws-reference/aws-reference destroy
./deploy/aws-reference/aws-reference audit
```

If `destroy` itself fails, run `audit` anyway to see exactly what is left, and delete it by hand
before re-running. Never leave the audit failing.
