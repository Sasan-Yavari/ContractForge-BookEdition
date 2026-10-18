variable "region" {
  type = string
}
variable "state_bucket_name" {
  type        = string
  description = "Globally unique name for the encrypted Terraform state bucket."
}
