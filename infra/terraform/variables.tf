variable "name" {
  type    = string
  default = "url-shortener"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "shard_count" {
  type    = number
  default = 3
}

variable "db_username" {
  type    = string
  default = "shortener"
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "app_desired_count" {
  type    = number
  default = 2
}

variable "lambda_jar_path" {
  type    = string
  default = "../../lambda/target/click-processor.jar"
}
