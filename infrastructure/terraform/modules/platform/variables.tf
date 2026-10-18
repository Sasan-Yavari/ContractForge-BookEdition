variable "environment" {
  type = string
  validation {
    condition     = contains(["Development", "Production"], var.environment)
    error_message = "Only Development and Production are supported."
  }
}
variable "short_environment" {
  type = string
  validation {
    condition     = contains(["dev", "prod"], var.short_environment)
    error_message = "Use dev or prod."
  }
}
variable "region" {
  type    = string
  default = "eu-west-1"
  validation {
    condition     = contains(["eu-central-1", "eu-north-1", "eu-south-1", "eu-south-2", "eu-west-1", "eu-west-3"], var.region)
    error_message = "The approved Nova 2 Lite EU inference profile is available only from its six supported EU source Regions."
  }
}
variable "domain_name" {
  type = string
}
variable "hosted_zone_id" {
  type = string
}
variable "cognito_domain_prefix" {
  type = string
}
variable "ses_sender_email" {
  type = string
}
variable "callback_urls" {
  type    = list(string)
  default = []
}
variable "logout_urls" {
  type    = list(string)
  default = []
}
variable "github_repository_identity" {
  description = "Immutable GitHub owner and repository identity used to scope AWS deployment-role OIDC subjects."
  type = object({
    owner         = string
    owner_id      = string
    name          = string
    repository_id = string
  })
  validation {
    condition     = can(regex("^[0-9]+$", var.github_repository_identity.owner_id)) && can(regex("^[0-9]+$", var.github_repository_identity.repository_id))
    error_message = "GitHub immutable owner and repository IDs must be numeric strings."
  }
}
variable "github_environment" {
  type = string
}
variable "promotion_peer_account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.promotion_peer_account_id))
    error_message = "Set the 12-digit AWS account ID for the other ContractForge environment."
  }
}
variable "promotion_peer_region" {
  type = string
  validation {
    condition     = contains(["eu-central-1", "eu-north-1", "eu-south-1", "eu-south-2", "eu-west-1", "eu-west-3"], var.promotion_peer_region)
    error_message = "The other environment must use one of the approved EU regions."
  }
}
variable "backend_cpu" {
  type    = number
  default = 1024
}
variable "backend_memory" {
  type    = number
  default = 2048
}
variable "frontend_cpu" {
  type    = number
  default = 256
}
variable "frontend_memory" {
  type    = number
  default = 512
}
variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "db_allocated_storage" {
  type    = number
  default = 20
}
variable "db_backup_retention_days" {
  type = number
}
variable "db_deletion_protection" {
  type = bool
}
variable "db_multi_az" {
  type    = bool
  default = false
}
variable "deletion_tombstones_deletion_protection" {
  type = bool
}
variable "log_retention_days" {
  type    = number
  default = 30
}
variable "operations_alert_email" {
  type      = string
  sensitive = true
  validation {
    condition     = trimspace(var.operations_alert_email) != ""
    error_message = "Set an owner-approved operations alert subscription endpoint."
  }
}
variable "monthly_budget_limit_usd" {
  type = number
  validation {
    condition     = var.monthly_budget_limit_usd > 0
    error_message = "Set the owner-approved monthly AWS cost budget in USD."
  }
}
variable "monthly_budget_alert_percentage" {
  type = number
  validation {
    condition     = var.monthly_budget_alert_percentage > 0
    error_message = "Set the owner-approved actual-spend percentage for the monthly budget notification."
  }
}
variable "rds_connection_alarm_threshold" {
  type = number
  validation {
    condition     = var.rds_connection_alarm_threshold > 0
    error_message = "Set the owner-selected RDS connection headroom alarm threshold."
  }
}
variable "api_health_check_failure_threshold" {
  type = number
  validation {
    condition     = var.api_health_check_failure_threshold >= 1 && var.api_health_check_failure_threshold <= 10
    error_message = "Set the Route 53 health-check failure count from 1 through 10."
  }
}
variable "tags" {
  type    = map(string)
  default = {}
}
