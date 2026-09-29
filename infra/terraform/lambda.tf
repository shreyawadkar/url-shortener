resource "aws_iam_role" "lambda" {
  name = "${var.name}-click-processor"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_msk" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaMSKExecutionRole"
}

resource "aws_lambda_function" "click_processor" {
  function_name    = "${var.name}-click-processor"
  role             = aws_iam_role.lambda.arn
  runtime          = "java17"
  handler          = "com.shortener.lambda.ClickEventHandler::handleRequest"
  filename         = var.lambda_jar_path
  source_code_hash = filebase64sha256(var.lambda_jar_path)
  memory_size      = 512
  timeout          = 60

  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.app.id]
  }

  environment {
    variables = merge(
      { for i, url in local.shard_jdbc_urls : "SHARD${i}_URL" => url },
      { DB_USER = var.db_username, DB_PASSWORD = var.db_password }
    )
  }
}

resource "aws_lambda_event_source_mapping" "clicks" {
  event_source_arn  = aws_msk_cluster.kafka.arn
  function_name     = aws_lambda_function.click_processor.arn
  topics            = ["click-events"]
  starting_position = "LATEST"
  batch_size        = 500
}
