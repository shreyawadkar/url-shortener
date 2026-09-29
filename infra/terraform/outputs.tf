output "app_url" {
  value = "http://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  value = aws_ecr_repository.app.repository_url
}

output "shard_endpoints" {
  value = aws_db_instance.shard[*].endpoint
}

output "kafka_bootstrap_brokers" {
  value = aws_msk_cluster.kafka.bootstrap_brokers
}

output "cloudwatch_dashboard" {
  value = "https://${var.region}.console.aws.amazon.com/cloudwatch/home?region=${var.region}#dashboards:name=${var.name}"
}
