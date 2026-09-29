# ---- MySQL: one RDS instance per shard ----
resource "aws_db_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "shard" {
  count                        = var.shard_count
  identifier                   = "${var.name}-shard-${count.index}"
  engine                       = "mysql"
  engine_version               = "8.0"
  instance_class               = "db.t4g.micro"
  allocated_storage            = 20
  db_name                      = "shortener"
  username                     = var.db_username
  password                     = var.db_password
  db_subnet_group_name         = aws_db_subnet_group.main.name
  vpc_security_group_ids       = [aws_security_group.data.id]
  skip_final_snapshot          = true
  publicly_accessible          = false
  performance_insights_enabled = false
}

# ---- Redis ----
resource "aws_elasticache_subnet_group" "main" {
  name       = var.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_cluster" "redis" {
  cluster_id         = "${var.name}-redis"
  engine             = "redis"
  node_type          = "cache.t4g.micro"
  num_cache_nodes    = 1
  port               = 6379
  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.data.id]
}

# ---- Kafka (MSK) ----
resource "aws_msk_cluster" "kafka" {
  cluster_name           = var.name
  kafka_version          = "3.6.0"
  number_of_broker_nodes = length(local.azs)

  broker_node_group_info {
    instance_type   = "kafka.t3.small"
    client_subnets  = aws_subnet.private[*].id
    security_groups = [aws_security_group.data.id]
    storage_info {
      ebs_storage_info {
        volume_size = 10
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }
}
