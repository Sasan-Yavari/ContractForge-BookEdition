locals {
  name                           = "contractforge-${var.short_environment}"
  github_oidc_subject            = format("repo:%s@%s/%s@%s:environment:%s", var.github_repository_identity.owner, var.github_repository_identity.owner_id, var.github_repository_identity.name, var.github_repository_identity.repository_id, var.github_environment)
  backend_name                   = "${local.name}-backend"
  frontend_name                  = "${local.name}-frontend"
  database_identifier            = "${local.name}-postgres"
  api_resource_server_id         = "contractforge-api"
  api_resource_server_scope      = "${local.api_resource_server_id}/access"
  cognito_hosted_domain          = "https://${var.cognito_domain_prefix}.auth.${var.region}.amazoncognito.com"
  cognito_scopes                 = ["openid", "email", "profile", local.api_resource_server_scope]
  cognito_callback_urls          = length(var.callback_urls) > 0 ? var.callback_urls : ["https://${var.domain_name}/"]
  cognito_logout_urls            = length(var.logout_urls) > 0 ? var.logout_urls : ["https://${var.domain_name}/"]
  development_repository_arns    = [for name in ["backend", "frontend"] : "arn:aws:ecr:${var.promotion_peer_region}:${var.promotion_peer_account_id}:repository/reader-example/${name}"]
  production_deployment_role_arn = "arn:aws:iam::${var.promotion_peer_account_id}:role/reader-example-github-deploy"
  frontend_env = [
    { name = "CONTRACTFORGE_RUNTIME_ENV", value = var.environment },
    { name = "CONTRACTFORGE_AUTH_MODE", value = "cognito" },
    { name = "CONTRACTFORGE_S3_UPLOAD_ORIGIN", value = "https://${aws_s3_bucket.documents.bucket}.s3.${var.region}.amazonaws.com" },
    { name = "CONTRACTFORGE_COGNITO_ISSUER_URI", value = local.cognito_issuer },
    { name = "CONTRACTFORGE_COGNITO_DOMAIN", value = local.cognito_hosted_domain },
    { name = "CONTRACTFORGE_COGNITO_CLIENT_ID", value = aws_cognito_user_pool_client.browser.id },
    { name = "CONTRACTFORGE_COGNITO_SCOPES", value = join(" ", local.cognito_scopes) },
    { name = "CONTRACTFORGE_COGNITO_CALLBACK_URL", value = local.cognito_callback_urls[0] },
    { name = "CONTRACTFORGE_COGNITO_LOGOUT_URL", value = local.cognito_logout_urls[0] }
  ]
  cognito_issuer             = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.app.id}"
  bedrock_eu_profile_regions = ["eu-central-1", "eu-north-1", "eu-south-1", "eu-south-2", "eu-west-1", "eu-west-3"]
  bedrock_eu_profile_arn     = "arn:aws:bedrock:${var.region}:${data.aws_caller_identity.current.account_id}:inference-profile/eu.amazon.nova-2-lite-v1:0"
  backend_env = [
    { name = "SPRING_PROFILES_ACTIVE", value = "aws" },
    { name = "AWS_REGION", value = var.region },
    { name = "CONTRACTFORGE_DB_URL", value = "jdbc:aws-wrapper:postgresql://${aws_db_instance.app.address}:5432/contractforge?sslmode=require" },
    { name = "CONTRACTFORGE_DB_SECRET_ARN", value = aws_db_instance.app.master_user_secret[0].secret_arn },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_TOMBSTONE_TABLE", value = aws_dynamodb_table.account_deletion_tombstones.name },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_FINGERPRINT_KEY_ARN", value = aws_kms_key.account_deletion_fingerprint.arn },
    { name = "CONTRACTFORGE_DB_RESOURCE_ID", value = aws_db_instance.app.resource_id },
    { name = "CONTRACTFORGE_COGNITO_ISSUER_URI", value = local.cognito_issuer },
    { name = "CONTRACTFORGE_COGNITO_CLIENT_ID", value = aws_cognito_user_pool_client.browser.id },
    { name = "CONTRACTFORGE_COGNITO_USER_POOL_ID", value = aws_cognito_user_pool.app.id },
    { name = "CONTRACTFORGE_COGNITO_USERINFO_URI", value = "${local.cognito_hosted_domain}/oauth2/userInfo" },
    { name = "CONTRACTFORGE_S3_QUARANTINE_BUCKET", value = aws_s3_bucket.documents.bucket },
    { name = "CONTRACTFORGE_S3_TRUSTED_OBJECT_KEY_PREFIX", value = "private/trusted/documents/" },
    { name = "CONTRACTFORGE_API_UPLOAD_RATE_LIMIT_MAXIMUM_REQUESTS", value = "5" },
    { name = "CONTRACTFORGE_API_UPLOAD_RATE_LIMIT_WINDOW", value = "PT1M" },
    { name = "CONTRACTFORGE_API_UPLOAD_RATE_LIMIT_MAX_TRACKED_USERS", value = "10000" },
    { name = "CONTRACTFORGE_INGESTION_QUEUE_URL", value = aws_sqs_queue.main["ingestion"].url },
    { name = "CONTRACTFORGE_PROCESSING_QUEUE_URL", value = aws_sqs_queue.main["processing"].url },
    { name = "CONTRACTFORGE_PROCESSING_DLQ_URL", value = aws_sqs_queue.dead_letter["processing"].url },
    { name = "CONTRACTFORGE_PROCESSING_STALE_ATTEMPT_AFTER", value = "PT${local.processing_stale_attempt_after_seconds}S" },
    { name = "CONTRACTFORGE_DOCUMENT_DELETION_QUEUE_URL", value = aws_sqs_queue.main["deletion"].url },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_QUEUE_URL", value = aws_sqs_queue.main["account-deletion"].url },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_IDENTITY_FENCE_DURATION", value = "PT70M" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_UPLOAD_WRITE_GRACE", value = "PT5M" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_QUEUE_POLL_SECONDS", value = "5" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_DISPATCH_BATCH_SIZE", value = "20" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_DISPATCH_LEASE", value = "PT1M" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_WORKER_LEASE", value = "PT4M" },
    { name = "CONTRACTFORGE_ACCOUNT_DELETION_VISIBILITY_TIMEOUT", value = "PT5M" },
    { name = "CONTRACTFORGE_ACCOUNTING_QUEUE_URL", value = aws_sqs_queue.main["accounting"].url },
    { name = "CONTRACTFORGE_ACCOUNTING_DLQ_URL", value = aws_sqs_queue.dead_letter["accounting"].url },
    { name = "CONTRACTFORGE_NOTIFICATION_QUEUE_URL", value = aws_sqs_queue.main["notification"].url },
    { name = "CONTRACTFORGE_NOTIFICATION_DLQ_URL", value = aws_sqs_queue.dead_letter["notification"].url },
    { name = "CONTRACTFORGE_TEXTRACT_REGION", value = var.region },
    { name = "CONTRACTFORGE_BEDROCK_REGION", value = var.region },
    { name = "CONTRACTFORGE_BEDROCK_MODEL_ID", value = "eu.amazon.nova-2-lite-v1:0" },
    { name = "CONTRACTFORGE_NOTIFICATION_EMAIL_ENABLED", value = "true" },
    { name = "CONTRACTFORGE_NOTIFICATION_EMAIL_SENDER", value = var.ses_sender_email },
    { name = "CONTRACTFORGE_NOTIFICATION_EMAIL_REGION", value = var.region },
    { name = "CONTRACTFORGE_NOTIFICATION_LEASE_DURATION", value = "PT1M" },
    { name = "CONTRACTFORGE_NOTIFICATION_MAX_ATTEMPTS", value = "5" },
    { name = "CONTRACTFORGE_MONITORING_SCHEDULE_ENABLED", value = "true" },
    { name = "CONTRACTFORGE_MONITORING_SCHEDULE_CRON", value = "0 0 2 * * *" },
    { name = "CONTRACTFORGE_MONITORING_SCHEDULE_ZONE", value = "UTC" },
    { name = "CONTRACTFORGE_NOTIFICATION_RECOVERY_ENABLED", value = "false" },
    { name = "CONTRACTFORGE_INGESTION_QUEUE_POLL_SECONDS", value = "5" }
  ]
  queue_specs = {
    ingestion          = { visibility = 300, retention = 1209600, max_receive_count = 12 }
    processing         = { visibility = local.processing_visibility_timeout_seconds, retention = 1209600, max_receive_count = 8 }
    accounting         = { visibility = 120, retention = 1209600, max_receive_count = 5 }
    notification       = { visibility = 120, retention = 1209600, max_receive_count = 5 }
    deletion           = { visibility = 120, retention = 1209600, max_receive_count = 5 }
    "account-deletion" = { visibility = 300, retention = 1209600, max_receive_count = 5 }
  }
  common_tags = merge({ Application = "ContractForge", Environment = var.environment, ManagedBy = "Terraform" }, var.tags)
}

moved {
  from = aws_cloudwatch_metric_alarm.account_deletion_dlq_visible
  to   = aws_cloudwatch_metric_alarm.dlq_visible["account-deletion"]
}

locals {
  processing_visibility_timeout_seconds    = 900
  processing_stale_attempt_after_seconds   = 3600
  processing_stale_recovery_margin_seconds = 1800
}

data "aws_availability_zones" "available" {
  state = "available"
}
data "aws_caller_identity" "current" {
}
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

resource "aws_vpc" "app" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.common_tags, { Name = "${local.name}-vpc" })
}

resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id
  tags   = local.common_tags
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.app.id
  cidr_block              = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index)
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = false
  tags                    = merge(local.common_tags, { Name = "${local.name}-public-${count.index + 1}" })
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.app.id
  cidr_block        = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index + 10)
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags              = merge(local.common_tags, { Name = "${local.name}-private-${count.index + 1}" })
}

resource "aws_subnet" "database" {
  count             = 2
  vpc_id            = aws_vpc.app.id
  cidr_block        = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index + 20)
  availability_zone = data.aws_availability_zones.available.names[count.index]
  tags              = merge(local.common_tags, { Name = "${local.name}-database-${count.index + 1}" })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.app.id
  tags   = local.common_tags
}
resource "aws_route" "public_default" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.app.id
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = local.common_tags
}
resource "aws_nat_gateway" "app" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  depends_on    = [aws_internet_gateway.app]
  tags          = local.common_tags
}
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.app.id
  tags   = local.common_tags
}
resource "aws_route" "private_default" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.app.id
}
resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.app.id
  tags   = local.common_tags
}
resource "aws_route_table_association" "database" {
  count          = 2
  subnet_id      = aws_subnet.database[count.index].id
  route_table_id = aws_route_table.database.id
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "Public HTTPS ingress"
  vpc_id      = aws_vpc.app.id
  tags        = local.common_tags
}
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "HTTPS browser traffic"
}
resource "aws_vpc_security_group_egress_rule" "alb_frontend" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.frontend.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Forward to frontend tasks"
}
resource "aws_security_group" "frontend" {
  name        = "${local.name}-frontend"
  description = "Frontend tasks reachable only from ALB"
  vpc_id      = aws_vpc.app.id
  tags        = local.common_tags
}
resource "aws_vpc_security_group_ingress_rule" "frontend_from_alb" {
  security_group_id            = aws_security_group.frontend.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Frontend HTTP from the public ALB"
}
resource "aws_vpc_security_group_egress_rule" "frontend_backend" {
  security_group_id            = aws_security_group.frontend.id
  referenced_security_group_id = aws_security_group.backend.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Nginx API proxy to backend"
}
resource "aws_vpc_security_group_egress_rule" "frontend_https" {
  security_group_id = aws_security_group.frontend.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "Private image pulls and managed log delivery through NAT"
}
resource "aws_security_group" "backend" {
  name        = "${local.name}-backend"
  description = "Private backend tasks"
  vpc_id      = aws_vpc.app.id
  tags        = local.common_tags
}
resource "aws_vpc_security_group_ingress_rule" "backend_from_frontend" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.frontend.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Backend API from frontend tasks"
}
resource "aws_vpc_security_group_egress_rule" "backend_https" {
  security_group_id = aws_security_group.backend.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "AWS and managed-provider HTTPS APIs through NAT"
}
resource "aws_vpc_security_group_egress_rule" "backend_database" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.database.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL to the private database"
}
resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "PostgreSQL only from backend"
  vpc_id      = aws_vpc.app.id
  tags        = local.common_tags
}
resource "aws_vpc_security_group_ingress_rule" "database_from_backend" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.backend.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from backend tasks"
}

resource "aws_acm_certificate" "app" {
  domain_name       = var.domain_name
  validation_method = "DNS"
  lifecycle {
    create_before_destroy = true
  }
  tags = local.common_tags
}
resource "aws_route53_record" "certificate_validation" {
  for_each        = { for dvo in aws_acm_certificate.app.domain_validation_options : dvo.domain_name => { name = dvo.resource_record_name, record = dvo.resource_record_value, type = dvo.resource_record_type } }
  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}
resource "aws_acm_certificate_validation" "app" {
  certificate_arn         = aws_acm_certificate.app.arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}
resource "aws_lb" "app" {
  name                       = "${local.name}-alb"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  enable_deletion_protection = var.environment == "Production"
  drop_invalid_header_fields = true
  tags                       = local.common_tags
}
resource "aws_lb_target_group" "frontend" {
  name        = "${local.name}-frontend"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.app.id
  target_type = "ip"
  health_check {
    enabled             = true
    path                = "/"
    protocol            = "HTTP"
    matcher             = "200-399"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
  tags = local.common_tags
}
resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = aws_acm_certificate_validation.app.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}
resource "aws_route53_record" "app" {
  zone_id = var.hosted_zone_id
  name    = var.domain_name
  type    = "A"
  alias {
    name                   = aws_lb.app.dns_name
    zone_id                = aws_lb.app.zone_id
    evaluate_target_health = true
  }
}

resource "aws_kms_key" "database" {
  description             = "${local.name} RDS encryption"
  enable_key_rotation     = true
  deletion_window_in_days = var.environment == "Production" ? 30 : 7
  tags                    = local.common_tags

  lifecycle {
    prevent_destroy = true
  }
}
resource "aws_kms_alias" "database" {
  name          = "alias/${local.name}-rds"
  target_key_id = aws_kms_key.database.key_id
}
resource "aws_kms_key" "account_deletion_fingerprint" {
  description              = "${local.name} account-deletion HMAC fingerprints"
  customer_master_key_spec = "HMAC_256"
  key_usage                = "GENERATE_VERIFY_MAC"
  enable_key_rotation      = false
  deletion_window_in_days  = var.environment == "Production" ? 30 : 7
  tags                     = local.common_tags

  lifecycle {
    prevent_destroy = true
  }
}
resource "aws_kms_alias" "account_deletion_fingerprint" {
  name          = "alias/${local.name}-account-deletion-fingerprint"
  target_key_id = aws_kms_key.account_deletion_fingerprint.key_id
}
resource "aws_dynamodb_table" "account_deletion_tombstones" {
  name                        = "${local.name}-account-deletion-tombstones"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "record_id"
  deletion_protection_enabled = var.deletion_tombstones_deletion_protection

  attribute {
    name = "record_id"
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }

  point_in_time_recovery {
    enabled = false
  }

  # Keeps the HMAC key behind the ledger during destroy so the key cannot be
  # scheduled for deletion while its tombstones still exist.
  tags = merge(local.common_tags, { FingerprintKeyArn = aws_kms_key.account_deletion_fingerprint.arn })
}
resource "aws_cloudwatch_log_group" "database" {
  for_each          = toset(["postgresql", "upgrade"])
  name              = "/aws/rds/instance/${local.database_identifier}/${each.key}"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}
resource "aws_db_subnet_group" "app" {
  name       = "${local.name}-database"
  subnet_ids = aws_subnet.database[*].id
  tags       = local.common_tags
}
resource "aws_db_instance" "app" {
  identifier                      = local.database_identifier
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.db_instance_class
  allocated_storage               = var.db_allocated_storage
  max_allocated_storage           = var.db_allocated_storage * 2
  db_name                         = "contractforge"
  username                        = "contractforge_admin"
  manage_master_user_password     = true
  port                            = 5432
  db_subnet_group_name            = aws_db_subnet_group.app.name
  parameter_group_name            = aws_db_parameter_group.app.name
  vpc_security_group_ids          = [aws_security_group.database.id]
  publicly_accessible             = false
  storage_encrypted               = true
  kms_key_id                      = aws_kms_key.database.arn
  backup_retention_period         = var.db_backup_retention_days
  deletion_protection             = var.db_deletion_protection
  multi_az                        = var.db_multi_az
  copy_tags_to_snapshot           = true
  auto_minor_version_upgrade      = true
  apply_immediately               = false
  skip_final_snapshot             = true
  delete_automated_backups        = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  tags                            = local.common_tags
  depends_on                      = [aws_cloudwatch_log_group.database, aws_dynamodb_table.account_deletion_tombstones]
}

resource "aws_db_parameter_group" "app" {
  name   = "${local.name}-postgres"
  family = "postgres16"

  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  tags = local.common_tags
}

resource "aws_s3_bucket" "documents" {
  bucket        = "${local.name}-${data.aws_caller_identity.current.account_id}-${var.region}-documents"
  force_destroy = false
  tags          = local.common_tags
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    id     = "expire-quarantine-incoming"
    status = "Enabled"

    filter {
      prefix = "quarantine/incoming/"
    }

    expiration {
      days = 16
    }
  }
}

resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
resource "aws_s3_bucket_cors_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  cors_rule {
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = ["https://${var.domain_name}"]
    allowed_headers = ["content-type", "x-amz-*", "authorization"]
    expose_headers  = ["ETag"]
    max_age_seconds = 300
  }
}
resource "aws_s3_bucket_policy" "documents_tls_only" {
  bucket     = aws_s3_bucket.documents.id
  policy     = jsonencode({ Version = "2012-10-17", Statement = [{ Sid = "DenyInsecureTransport", Effect = "Deny", Principal = "*", Action = "s3:*", Resource = [aws_s3_bucket.documents.arn, "${aws_s3_bucket.documents.arn}/*"], Condition = { Bool = { "aws:SecureTransport" = "false" } } }] })
  depends_on = [aws_s3_bucket_public_access_block.documents]
}

resource "aws_sqs_queue_policy" "ingestion_source" {
  queue_url = aws_sqs_queue.main["ingestion"].url
  policy    = jsonencode({ Version = "2012-10-17", Statement = [{ Sid = "AllowQuarantineBucketObjectCreated", Effect = "Allow", Principal = { Service = "s3.amazonaws.com" }, Action = "sqs:SendMessage", Resource = aws_sqs_queue.main["ingestion"].arn, Condition = { ArnEquals = { "aws:SourceArn" = aws_s3_bucket.documents.arn }, StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } } }] })
}

resource "aws_s3_bucket_notification" "documents" {
  bucket      = aws_s3_bucket.documents.id
  eventbridge = true
  queue {
    queue_arn     = aws_sqs_queue.main["ingestion"].arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "quarantine/incoming/"
  }
  depends_on = [aws_sqs_queue_policy.ingestion_source]
}

resource "aws_iam_role" "guardduty_s3_scan" {
  name               = "${local.name}-guardduty-s3-malware"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "malware-protection-plan.guardduty.amazonaws.com" }, Action = "sts:AssumeRole", Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } } }] })
  tags               = local.common_tags
}
resource "aws_iam_role_policy" "guardduty_s3_scan" {
  name = "${local.name}-guardduty-s3-malware"
  role = aws_iam_role.guardduty_s3_scan.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "ManageGuardDutyOwnedEventRule", Effect = "Allow", Action = ["events:PutRule", "events:DeleteRule", "events:PutTargets", "events:RemoveTargets"], Resource = "arn:aws:events:${var.region}:${data.aws_caller_identity.current.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*", Condition = { StringLike = { "events:ManagedBy" = "malware-protection-plan.guardduty.amazonaws.com" } } },
    { Sid = "InspectGuardDutyOwnedEventRule", Effect = "Allow", Action = ["events:DescribeRule", "events:ListTargetsByRule"], Resource = "arn:aws:events:${var.region}:${data.aws_caller_identity.current.account_id}:rule/DO-NOT-DELETE-AmazonGuardDutyMalwareProtectionS3*" },
    { Sid = "EnableBucketEventBridgeNotifications", Effect = "Allow", Action = ["s3:GetBucketNotification", "s3:PutBucketNotification"], Resource = aws_s3_bucket.documents.arn },
    { Sid = "CheckBucketAndCreateValidationObject", Effect = "Allow", Action = ["s3:GetBucketLocation", "s3:ListBucket"], Resource = aws_s3_bucket.documents.arn },
    { Sid = "WriteGuardDutyValidationObject", Effect = "Allow", Action = "s3:PutObject", Resource = "${aws_s3_bucket.documents.arn}/malware-protection-resource-validation-object" },
    { Sid = "ScanQuarantineObjects", Effect = "Allow", Action = ["s3:GetObject", "s3:GetObjectVersion"], Resource = "${aws_s3_bucket.documents.arn}/quarantine/incoming/*" },
    { Sid = "ReadAndWriteScanStatusTags", Effect = "Allow", Action = ["s3:GetObjectTagging", "s3:PutObjectTagging", "s3:GetObjectVersionTagging", "s3:PutObjectVersionTagging"], Resource = "${aws_s3_bucket.documents.arn}/quarantine/incoming/*" }
  ] })
}
resource "aws_guardduty_malware_protection_plan" "documents" {
  role = aws_iam_role.guardduty_s3_scan.arn
  protected_resource {
    s3_bucket {
      bucket_name     = aws_s3_bucket.documents.id
      object_prefixes = ["quarantine/incoming/"]
    }
  }
  actions {
    tagging {
      status = "ENABLED"
    }
  }
  tags       = local.common_tags
  depends_on = [aws_iam_role_policy.guardduty_s3_scan]
}

resource "aws_sqs_queue" "dead_letter" {
  for_each                  = local.queue_specs
  name                      = "${local.name}-${each.key}-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
  tags                      = local.common_tags
}
resource "aws_sqs_queue" "main" {
  for_each                   = local.queue_specs
  name                       = "${local.name}-${each.key}"
  visibility_timeout_seconds = each.value.visibility
  message_retention_seconds  = each.value.retention
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.dead_letter[each.key].arn, maxReceiveCount = each.value.max_receive_count })
  tags                       = local.common_tags

  lifecycle {
    precondition {
      condition = each.key != "processing" || each.value.max_receive_count >= ceil(
        (local.processing_stale_attempt_after_seconds + local.processing_stale_recovery_margin_seconds + each.value.visibility)
        / each.value.visibility
      ) + 1
      error_message = "Processing queue redrive must leave a stale-attempt recovery delivery with the configured safety margin."
    }
  }
}

resource "aws_sns_topic" "operations" {
  name = "${local.name}-operations-alerts"
  tags = local.common_tags
}

resource "aws_sns_topic_subscription" "operations_email" {
  topic_arn = aws_sns_topic.operations.arn
  protocol  = "email"
  endpoint  = var.operations_alert_email
}

resource "aws_sns_topic_policy" "operations" {
  arn = aws_sns_topic.operations.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountOwnerManagement"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action = [
          "sns:AddPermission",
          "sns:DeleteTopic",
          "sns:GetDataProtectionPolicy",
          "sns:GetTopicAttributes",
          "sns:ListSubscriptionsByTopic",
          "sns:ListTagsForResource",
          "sns:PutDataProtectionPolicy",
          "sns:RemovePermission",
          "sns:SetTopicAttributes",
          "sns:Subscribe",
        ]
        Resource = aws_sns_topic.operations.arn
      },
      {
        Sid       = "AllowCloudWatchAlarmPublish"
        Effect    = "Allow"
        Principal = { Service = "cloudwatch.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.operations.arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:cloudwatch:${var.region}:${data.aws_caller_identity.current.account_id}:alarm:${local.name}-*" }
        }
      },
      {
        Sid       = "AllowBudgetPublish"
        Effect    = "Allow"
        Principal = { Service = "budgets.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.operations.arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
          ArnLike      = { "aws:SourceArn" = "arn:aws:budgets::${data.aws_caller_identity.current.account_id}:budget/${local.name}-monthly-cost" }
        }
      },
      {
        Sid       = "AllowRdsEventPublish"
        Effect    = "Allow"
        Principal = { Service = "events.rds.amazonaws.com" }
        Action    = "sns:Publish"
        Resource  = aws_sns_topic.operations.arn
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
          ArnLike      = { "aws:SourceArn" = aws_db_instance.app.arn }
        }
      }
    ]
  })
}

resource "aws_budgets_budget" "monthly_cost" {
  name         = "${local.name}-monthly-cost"
  account_id   = data.aws_caller_identity.current.account_id
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_limit_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = var.monthly_budget_alert_percentage
    threshold_type            = "PERCENTAGE"
    notification_type         = "ACTUAL"
    subscriber_sns_topic_arns = [aws_sns_topic.operations.arn]
  }

  depends_on = [aws_sns_topic_policy.operations]
}

resource "aws_cloudwatch_metric_alarm" "dlq_visible" {
  for_each = aws_sqs_queue.dead_letter

  alarm_name = "${local.name}-${each.key}-dlq-visible"
  alarm_description = each.key == "processing" ? (
    "Processing DLQ work is normally terminalized by the application consumer; visible messages require investigation of terminalization or malformed-message handling."
  ) : "${each.key} DLQ has visible work requiring investigation and boundary-specific recovery."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value.name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_route53_health_check" "api" {
  fqdn              = var.domain_name
  port              = 443
  type              = "HTTPS_STR_MATCH"
  resource_path     = "/v1/health"
  search_string     = "UP"
  failure_threshold = var.api_health_check_failure_threshold
  request_interval  = 30
  measure_latency   = false
  tags              = merge(local.common_tags, { Name = "${local.name}-api-health" })
}

resource "aws_cloudwatch_metric_alarm" "api_health" {
  alarm_name          = "${local.name}-api-health"
  alarm_description   = "The public HTTPS API health path is failing Route 53 checks."
  namespace           = "AWS/Route53"
  metric_name         = "HealthCheckStatus"
  dimensions          = { HealthCheckId = aws_route53_health_check.api.id }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "backend_tasks" {
  alarm_name          = "${local.name}-backend-task-shortfall"
  alarm_description   = "The backend has fewer running tasks than its desired count. It remains quiet while the service is intentionally at zero tasks."
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags

  metric_query {
    id          = "desired"
    return_data = false

    metric {
      metric_name = "DesiredTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Maximum"
      dimensions  = { ClusterName = aws_ecs_cluster.app.name, ServiceName = aws_ecs_service.backend.name }
    }
  }

  metric_query {
    id          = "running"
    return_data = false

    metric {
      metric_name = "RunningTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Minimum"
      dimensions  = { ClusterName = aws_ecs_cluster.app.name, ServiceName = aws_ecs_service.backend.name }
    }
  }

  metric_query {
    id          = "shortfall"
    expression  = "desired - running"
    label       = "Backend desired task shortfall"
    return_data = true
  }
}

resource "aws_cloudwatch_metric_alarm" "frontend_tasks" {
  alarm_name          = "${local.name}-frontend-task-shortfall"
  alarm_description   = "The frontend has fewer running tasks than its desired count. It remains quiet while the service is intentionally at zero tasks."
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags

  metric_query {
    id          = "desired"
    return_data = false

    metric {
      metric_name = "DesiredTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Maximum"
      dimensions  = { ClusterName = aws_ecs_cluster.app.name, ServiceName = aws_ecs_service.frontend.name }
    }
  }

  metric_query {
    id          = "running"
    return_data = false

    metric {
      metric_name = "RunningTaskCount"
      namespace   = "ECS/ContainerInsights"
      period      = 60
      stat        = "Minimum"
      dimensions  = { ClusterName = aws_ecs_cluster.app.name, ServiceName = aws_ecs_service.frontend.name }
    }
  }

  metric_query {
    id          = "shortfall"
    expression  = "desired - running"
    label       = "Frontend desired task shortfall"
    return_data = true
  }
}

resource "aws_cloudwatch_metric_alarm" "alb_unhealthy_targets" {
  alarm_name          = "${local.name}-alb-unhealthy-targets"
  alarm_description   = "The ALB has no healthy frontend target."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  dimensions          = { LoadBalancer = aws_lb.app.arn_suffix, TargetGroup = aws_lb_target_group.frontend.arn_suffix }
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name}-alb-5xx"
  alarm_description   = "The ALB returned one or more target or load-balancer 5xx responses in the evaluation period."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.app.arn_suffix }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "alb_target_5xx" {
  alarm_name          = "${local.name}-alb-target-5xx"
  alarm_description   = "The frontend target returned one or more 5xx responses, including same-origin API proxy failures."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  dimensions          = { LoadBalancer = aws_lb.app.arn_suffix, TargetGroup = aws_lb_target_group.frontend.arn_suffix }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "database_connections" {
  alarm_name          = "${local.name}-database-connection-headroom"
  alarm_description   = "RDS DatabaseConnections reached the operator-configured connection headroom threshold."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBInstanceIdentifier = aws_db_instance.app.identifier }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = var.rds_connection_alarm_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_db_event_subscription" "database_operations" {
  name             = "${local.name}-database-operations"
  sns_topic        = aws_sns_topic.operations.arn
  source_type      = "db-instance"
  source_ids       = [aws_db_instance.app.identifier]
  event_categories = ["availability", "failure", "low storage"]
  enabled          = true
  tags             = local.common_tags
  depends_on       = [aws_sns_topic_policy.operations]
}

resource "aws_cloudwatch_log_metric_filter" "notification_delivery_count" {
  name           = "${local.name}-notification-delivery-count"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"notification_delivery_outcome\" }"

  metric_transformation {
    name       = "NotificationDeliveryCount"
    namespace  = "${local.name}/Application"
    value      = "1"
    dimensions = { Outcome = "$.outcome" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "notification_queue_age" {
  name           = "${local.name}-notification-queue-age"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"notification_delivery_outcome\" }"

  metric_transformation {
    name       = "NotificationQueueAgeMs"
    namespace  = "${local.name}/Application"
    value      = "$.queue_age_ms"
    unit       = "Milliseconds"
    dimensions = { Outcome = "$.outcome" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "notification_delivery_latency" {
  name           = "${local.name}-notification-delivery-latency"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"notification_delivery_outcome\" }"

  metric_transformation {
    name       = "NotificationDeliveryLatencyMs"
    namespace  = "${local.name}/Application"
    value      = "$.delivery_latency_ms"
    unit       = "Milliseconds"
    dimensions = { Outcome = "$.outcome" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "notification_publication_failure" {
  name           = "${local.name}-notification-publication-failure"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"notification_delivery_publication_failed\" }"

  metric_transformation {
    name      = "NotificationPublicationFailure"
    namespace = "${local.name}/Application"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "notification_publication_failure" {
  alarm_name          = "${local.name}-notification-publication-failure"
  alarm_description   = "A durable notification intent could not be published and requires operator investigation."
  namespace           = "${local.name}/Application"
  metric_name         = "NotificationPublicationFailure"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_log_metric_filter" "document_deletion_publication_failure" {
  name           = "${local.name}-document-deletion-publication-failure"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"document_deletion_publication_failed\" }"

  metric_transformation {
    name      = "DocumentDeletionPublicationFailure"
    namespace = "${local.name}/Application"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "document_deletion_publication_failure" {
  alarm_name          = "${local.name}-document-deletion-publication-failure"
  alarm_description   = "Document deletion committed but private-object cleanup could not be published; inspect the approved post-commit recovery gap."
  namespace           = "${local.name}/Application"
  metric_name         = "DocumentDeletionPublicationFailure"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_log_metric_filter" "provider_usage" {
  name           = "${local.name}-provider-usage"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"provider_usage_recorded\" }"

  metric_transformation {
    name       = "ProviderUsageRecordCount"
    namespace  = "${local.name}/ProviderUsage"
    value      = "1"
    dimensions = { Provider = "$.provider", Operation = "$.operation", CostStatus = "$.cost_status" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "provider_calculated_cost" {
  name           = "${local.name}-provider-calculated-cost"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"provider_usage_recorded\" && $.cost_status = \"CALCULATED\" }"

  metric_transformation {
    name       = "ProviderCalculatedCost"
    namespace  = "${local.name}/ProviderUsage"
    value      = "$.cost_amount"
    unit       = "None"
    dimensions = { Provider = "$.provider", Operation = "$.operation", Currency = "$.currency" }
  }
}

resource "aws_cloudwatch_log_metric_filter" "accounting_publication_failure" {
  name           = "${local.name}-accounting-publication-failure"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "{ $.event_type = \"accounting_usage_publication_failed\" }"

  metric_transformation {
    name      = "AccountingPublicationFailure"
    namespace = "${local.name}/Application"
    value     = "1"
    unit      = "Count"
  }
}

resource "aws_cloudwatch_metric_alarm" "accounting_publication_failure" {
  alarm_name          = "${local.name}-accounting-publication-failure"
  alarm_description   = "A paid provider usage event was not published; inspect the durable accounting boundary and preserve the known post-provider publication gap."
  namespace           = "${local.name}/Application"
  metric_name         = "AccountingPublicationFailure"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.operations.arn]
  tags                = local.common_tags
}

resource "aws_cloudwatch_query_definition" "provider_usage_cost" {
  name            = "${local.name}/provider-usage-cost"
  log_group_names = [aws_cloudwatch_log_group.backend.name]
  query_string    = <<-QUERY
    fields @timestamp, provider, operation, cost_status, cost_amount, currency
    | filter event_type = "provider_usage_recorded"
    | stats count(*) as usage_records, sum(cost_amount) as logged_cost by provider, operation, cost_status, currency
  QUERY
}

resource "aws_cloudwatch_query_definition" "notification_delivery" {
  name            = "${local.name}/notification-delivery"
  log_group_names = [aws_cloudwatch_log_group.backend.name]
  query_string    = <<-QUERY
    fields @timestamp, outcome, queue_age_ms, delivery_latency_ms
    | filter event_type = "notification_delivery_outcome"
    | stats count(*) as delivery_count, max(queue_age_ms) as max_queue_age_ms, max(delivery_latency_ms) as max_delivery_latency_ms by outcome
  QUERY
}

resource "aws_cognito_user_pool" "app" {
  name                     = local.name
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]
  deletion_protection      = var.environment == "Production" ? "ACTIVE" : "INACTIVE"
  admin_create_user_config {
    allow_admin_create_user_only = false
  }
  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 7
  }
  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
  schema {
    attribute_data_type = "String"
    name                = "email"
    required            = true
    mutable             = true
    string_attribute_constraints {
      min_length = 3
      max_length = 2048
    }
  }
  tags = local.common_tags
}
resource "aws_cognito_user_pool_domain" "app" {
  domain       = var.cognito_domain_prefix
  user_pool_id = aws_cognito_user_pool.app.id
}
resource "aws_cognito_resource_server" "api" {
  identifier   = local.api_resource_server_id
  name         = "ContractForge API"
  user_pool_id = aws_cognito_user_pool.app.id
  scope {
    scope_name        = "access"
    scope_description = "Access ContractForge API"
  }
}
resource "aws_cognito_user_pool_client" "browser" {
  name                                 = "${local.name}-browser"
  user_pool_id                         = aws_cognito_user_pool.app.id
  generate_secret                      = false
  prevent_user_existence_errors        = "ENABLED"
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile", local.api_resource_server_scope]
  callback_urls                        = local.cognito_callback_urls
  logout_urls                          = local.cognito_logout_urls
  supported_identity_providers         = ["COGNITO"]
  access_token_validity                = 60
  id_token_validity                    = 60
  refresh_token_validity               = 30
  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
  depends_on = [aws_cognito_user_pool_domain.app]
}

resource "aws_ecr_repository" "image" {
  for_each             = toset(["backend", "frontend"])
  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
  tags = local.common_tags
}
resource "aws_ecr_lifecycle_policy" "image" {
  for_each   = aws_ecr_repository.image
  repository = each.value.name
  policy     = jsonencode({ rules = [{ rulePriority = 1, description = "Retain the 30 newest rollback/release images", selection = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }, action = { type = "expire" } }] })
}
resource "aws_ecr_repository_policy" "production_promotion_read" {
  for_each   = var.environment == "Development" ? toset(["backend", "frontend"]) : toset([])
  repository = aws_ecr_repository.image[each.key].name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowProductionReleasePromotionRead"
      Effect    = "Allow"
      Principal = { AWS = local.production_deployment_role_arn }
      Action    = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer"]
    }]
  })
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.backend_name}"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}
resource "aws_cloudwatch_log_group" "frontend" {
  name              = "/ecs/${local.frontend_name}"
  retention_in_days = var.log_retention_days
  tags              = local.common_tags
}
resource "aws_ecs_cluster" "app" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
  tags = local.common_tags
}
resource "aws_service_discovery_private_dns_namespace" "app" {
  name        = "${local.name}.internal"
  vpc         = aws_vpc.app.id
  description = "Private service discovery for ${var.environment}"
}
resource "aws_iam_role" "task_execution" {
  name               = "${local.name}-task-execution"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole", Condition = { ArnEquals = { "aws:SourceArn" = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:*" }, StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } } }] })
  tags               = local.common_tags
}
resource "aws_iam_role_policy" "task_execution" {
  name = "${local.name}-execution"
  role = aws_iam_role.task_execution.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = [for repo in aws_ecr_repository.image : repo.arn] },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = ["${aws_cloudwatch_log_group.backend.arn}:*", "${aws_cloudwatch_log_group.frontend.arn}:*"] }
  ] })
}
resource "aws_iam_role" "backend_task" {
  name               = "${local.name}-backend-task"
  assume_role_policy = aws_iam_role.task_execution.assume_role_policy
  tags               = local.common_tags
}
resource "aws_iam_role_policy" "backend_task" {
  name = "${local.name}-application-access"
  role = aws_iam_role.backend_task.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "ReadCurrentDatabaseCredentials", Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = aws_db_instance.app.master_user_secret[0].secret_arn },
    { Sid = "ReadExplicitlyTaggedRestoreDatabaseCredential", Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:*", Condition = { StringEquals = { "secretsmanager:ResourceTag/ContractForgeRestoreTarget" = "${local.database_identifier}-restore" } } },
    { Sid = "ReadWriteAccountDeletionTombstones", Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:Scan"], Resource = aws_dynamodb_table.account_deletion_tombstones.arn },
    { Sid = "FingerprintProviderSubjects", Effect = "Allow", Action = "kms:GenerateMac", Resource = aws_kms_key.account_deletion_fingerprint.arn, Condition = { StringEquals = { "aws:RequestedRegion" = var.region } } },
    { Sid = "InspectRdsRecoveryPoints", Effect = "Allow", Action = ["rds:DescribeDBInstances", "rds:DescribeDBInstanceAutomatedBackups", "rds:DescribeDBSnapshots"], Resource = "*", Condition = { StringEquals = { "aws:RequestedRegion" = var.region } } },
    { Sid = "BucketPrefixListing", Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.documents.arn, Condition = { StringLike = { "s3:prefix" = ["quarantine/incoming/*", "private/trusted/documents/*"] } } },
    { Sid = "DocumentObjectOperations", Effect = "Allow", Action = ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject", "s3:PutObject"], Resource = ["${aws_s3_bucket.documents.arn}/quarantine/incoming/*", "${aws_s3_bucket.documents.arn}/private/trusted/documents/*"] },
    { Sid = "ReadQuarantineMalwareStatus", Effect = "Allow", Action = "s3:GetObjectTagging", Resource = "${aws_s3_bucket.documents.arn}/quarantine/incoming/*" },
    { Sid = "CopyQuarantineTagsToTrustedDocuments", Effect = "Allow", Action = "s3:PutObjectTagging", Resource = "${aws_s3_bucket.documents.arn}/private/trusted/documents/*" },
    { Sid = "QueueSend", Effect = "Allow", Action = ["sqs:SendMessage", "sqs:GetQueueAttributes"], Resource = [aws_sqs_queue.main["processing"].arn, aws_sqs_queue.main["accounting"].arn, aws_sqs_queue.main["notification"].arn, aws_sqs_queue.main["deletion"].arn, aws_sqs_queue.main["account-deletion"].arn] },
    { Sid = "WorkerReceiveAndAcknowledge", Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"], Resource = [aws_sqs_queue.main["ingestion"].arn, aws_sqs_queue.main["processing"].arn, aws_sqs_queue.main["accounting"].arn, aws_sqs_queue.main["notification"].arn, aws_sqs_queue.main["deletion"].arn, aws_sqs_queue.main["account-deletion"].arn] },
    { Sid = "AccountDeletionCognitoLifecycle", Effect = "Allow", Action = ["cognito-idp:AdminDisableUser", "cognito-idp:AdminDeleteUser"], Resource = aws_cognito_user_pool.app.arn },
    { Sid = "ProcessingTerminalQueue", Effect = "Allow", Action = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"], Resource = aws_sqs_queue.dead_letter["processing"].arn },
    { Sid = "TextractTextDetection", Effect = "Allow", Action = "textract:DetectDocumentText", Resource = "*", Condition = { StringEquals = { "aws:RequestedRegion" = var.region } } },
    { Sid = "BedrockApprovedInferenceProfile", Effect = "Allow", Action = "bedrock:InvokeModel", Resource = local.bedrock_eu_profile_arn },
    { Sid = "BedrockInferenceProfileDestinationModels", Effect = "Allow", Action = "bedrock:InvokeModel", Resource = [for region in local.bedrock_eu_profile_regions : "arn:aws:bedrock:${region}::foundation-model/amazon.nova-2-lite-v1:0"], Condition = { StringEquals = { "bedrock:InferenceProfileArn" = local.bedrock_eu_profile_arn } } },
    { Sid = "SesNotificationSender", Effect = "Allow", Action = "ses:SendEmail", Resource = "arn:aws:ses:${var.region}:${data.aws_caller_identity.current.account_id}:identity/${var.ses_sender_email}" }
  ] })
}

resource "aws_ecs_task_definition" "backend" {
  family                   = local.backend_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.backend_task.arn
  container_definitions = jsonencode([{
    name                   = "backend"
    image                  = "${aws_ecr_repository.image["backend"].repository_url}:bootstrap"
    essential              = true
    user                   = "10001:10001"
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp", name = "http" }]
    mountPoints            = [{ sourceVolume = "container-tmp", containerPath = "/tmp", readOnly = false }]
    environment            = local.backend_env
    healthCheck            = { command = ["CMD-SHELL", "wget -q -O - http://127.0.0.1:8080/v1/health | grep -q UP || exit 1"], interval = 30, timeout = 5, retries = 3, startPeriod = 90 }
    logConfiguration       = { logDriver = "awslogs", options = { "awslogs-group" = aws_cloudwatch_log_group.backend.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "backend" } }
  }])
  volume {
    name = "container-tmp"
  }
  tags = local.common_tags
}
resource "aws_ecs_task_definition" "frontend" {
  family                   = local.frontend_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.frontend_cpu
  memory                   = var.frontend_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  container_definitions = jsonencode([{
    name                   = "frontend"
    image                  = "${aws_ecr_repository.image["frontend"].repository_url}:bootstrap"
    essential              = true
    user                   = "101:101"
    readonlyRootFilesystem = true
    portMappings           = [{ containerPort = 8080, protocol = "tcp", name = "http" }]
    mountPoints            = [{ sourceVolume = "container-tmp", containerPath = "/tmp", readOnly = false }]
    environment            = local.frontend_env
    healthCheck            = { command = ["CMD-SHELL", "wget -q --spider http://127.0.0.1:8080/ || exit 1"], interval = 30, timeout = 5, retries = 3, startPeriod = 10 }
    logConfiguration       = { logDriver = "awslogs", options = { "awslogs-group" = aws_cloudwatch_log_group.frontend.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "frontend" } }
  }])
  volume {
    name = "container-tmp"
  }
  tags = local.common_tags
}
resource "aws_ecs_service" "backend" {
  name                               = local.backend_name
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = aws_ecs_task_definition.backend.arn
  desired_count                      = 0
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  enable_execute_command             = false
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.backend.id]
    assign_public_ip = false
  }
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_private_dns_namespace.app.arn
    service {
      discovery_name = "backend"
      port_name      = "http"
      client_alias {
        dns_name = "backend"
        port     = 8080
      }
    }
  }
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }
  tags = local.common_tags
}
resource "aws_ecs_service" "frontend" {
  name                               = local.frontend_name
  cluster                            = aws_ecs_cluster.app.id
  task_definition                    = aws_ecs_task_definition.frontend.arn
  desired_count                      = 0
  launch_type                        = "FARGATE"
  platform_version                   = "LATEST"
  enable_execute_command             = false
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = aws_subnet.private[*].id
    security_groups  = [aws_security_group.frontend.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 8080
  }
  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_private_dns_namespace.app.arn
  }
  lifecycle {
    ignore_changes = [desired_count, task_definition]
  }
  depends_on = [aws_lb_listener.https, aws_ecs_service.backend]
  tags       = local.common_tags
}

resource "aws_iam_role" "deployment" {
  name = "${local.name}-github-deploy"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = local.github_oidc_subject
        }
      }
    }]
  })
  tags = local.common_tags
}
resource "aws_iam_role_policy" "deployment" {
  name = "${local.name}-github-deploy"
  role = aws_iam_role.deployment.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "EcrLogin", Effect = "Allow", Action = "ecr:GetAuthorizationToken", Resource = "*" },
    { Sid = "PushAndResolveEnvironmentImages", Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages", "ecr:DescribeImageScanFindings"], Resource = [for repo in aws_ecr_repository.image : repo.arn] },
    { Sid = "RegisterTaskDefinitionRevision", Effect = "Allow", Action = "ecs:RegisterTaskDefinition", Resource = "*" },
    { Sid = "ReadTaskDefinition", Effect = "Allow", Action = "ecs:DescribeTaskDefinition", Resource = "*" },
    { Sid = "UpdateOnlyEnvironmentServices", Effect = "Allow", Action = "ecs:UpdateService", Resource = [aws_ecs_service.backend.arn, aws_ecs_service.frontend.arn] },
    { Sid = "ReadOnlyEnvironmentServices", Effect = "Allow", Action = "ecs:DescribeServices", Resource = [aws_ecs_cluster.app.arn, aws_ecs_service.backend.arn, aws_ecs_service.frontend.arn] },
    { Sid = "PassOnlyRuntimeRoles", Effect = "Allow", Action = "iam:PassRole", Resource = [aws_iam_role.task_execution.arn, aws_iam_role.backend_task.arn], Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } } }
  ] })
}
resource "aws_iam_role_policy" "promotion_source_read" {
  count = var.environment == "Production" ? 1 : 0
  name  = "${local.name}-promotion-source-read"
  role  = aws_iam_role.deployment.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "ReadOnlyDevelopmentReleaseImages"
      Effect   = "Allow"
      Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer"]
      Resource = local.development_repository_arns
    }]
  })
}

output "environment" {
  value = var.environment
}
output "region" {
  value = var.region
}
output "account_id" {
  value = data.aws_caller_identity.current.account_id
}
output "promotion_peer_account_id" {
  value = var.promotion_peer_account_id
}
output "promotion_peer_region" {
  value = var.promotion_peer_region
}
output "api_base_url" {
  value = "https://${var.domain_name}"
}
output "frontend_image_repository" {
  value = aws_ecr_repository.image["frontend"].repository_url
}
output "backend_image_repository" {
  value = aws_ecr_repository.image["backend"].repository_url
}
output "ecs_cluster_name" {
  value = aws_ecs_cluster.app.name
}
output "frontend_service_name" {
  value = aws_ecs_service.frontend.name
}
output "backend_service_name" {
  value = aws_ecs_service.backend.name
}
output "frontend_task_definition_family" {
  value = aws_ecs_task_definition.frontend.family
}
output "backend_task_definition_family" {
  value = aws_ecs_task_definition.backend.family
}
output "database_endpoint" {
  value = aws_db_instance.app.address
}
output "database_resource_id" {
  value = aws_db_instance.app.resource_id
}
output "database_identifier" {
  value = aws_db_instance.app.identifier
}
output "account_deletion_tombstone_table" {
  value = aws_dynamodb_table.account_deletion_tombstones.name
}
output "account_deletion_fingerprint_key_arn" {
  value = aws_kms_key.account_deletion_fingerprint.arn
}
output "database_secret_arn" {
  value = aws_db_instance.app.master_user_secret[0].secret_arn
}
output "document_bucket_name" {
  value = aws_s3_bucket.documents.bucket
}
output "guardduty_malware_plan_id" {
  value = aws_guardduty_malware_protection_plan.documents.id
}
output "queue_urls" {
  value = { for name, queue in aws_sqs_queue.main : name => queue.url }
}
output "dead_letter_queue_urls" {
  value = { for name, queue in aws_sqs_queue.dead_letter : name => queue.url }
}
output "cognito_issuer" {
  value = local.cognito_issuer
}
output "cognito_domain" {
  value = local.cognito_hosted_domain
}
output "cognito_client_id" {
  value = aws_cognito_user_pool_client.browser.id
}
output "cognito_resource_audience" {
  value = null
}
output "cognito_scopes" {
  value = local.cognito_scopes
}
output "callback_urls" {
  value = local.cognito_callback_urls
}
output "logout_urls" {
  value = local.cognito_logout_urls
}
output "github_deployment_role_arn" {
  value = aws_iam_role.deployment.arn
}
output "log_group_names" {
  value = concat(
    [aws_cloudwatch_log_group.backend.name, aws_cloudwatch_log_group.frontend.name],
    [for log_group in aws_cloudwatch_log_group.database : log_group.name]
  )
}
output "load_balancer_dns_name" {
  value = aws_lb.app.dns_name
}
output "load_balancer_arn" {
  value = aws_lb.app.arn
}
output "operations_alert_topic_arn" {
  value = aws_sns_topic.operations.arn
}
output "monthly_budget_name" {
  value = aws_budgets_budget.monthly_cost.name
}
