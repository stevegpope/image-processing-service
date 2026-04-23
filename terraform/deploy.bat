call mvn -f ../pom.xml clean package

terraform apply -auto-approve -var "lambda_artifact=..\target\image-processor-1.0.0.jar"