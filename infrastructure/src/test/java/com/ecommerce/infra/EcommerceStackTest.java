package com.ecommerce.infra;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.s3.Bucket;
import static org.junit.jupiter.api.Assertions.*;

class EcommerceStackTest {
    private Template template() {
        // A separate, local-only asset stack avoids building or uploading a JAR in unit tests.
        App app = new App();
        var assets = new software.amazon.awscdk.Stack(app, "TestAssets");
        var bucket = new Bucket(assets, "Bucket");
        return Template.fromStack(new EcommerceStack(app, "Test", StackProps.builder().build(),
                "test", Code.fromBucket(bucket, "services.jar")));
    }

    @Test void countsAndRuntime() {
        Template t = template();
        t.resourceCountIs("AWS::Lambda::Function", 4);
        t.resourceCountIs("AWS::DynamoDB::Table", 3);
        t.resourceCountIs("AWS::ApiGateway::RestApi", 3);
        t.resourceCountIs("AWS::ApiGateway::Method", 12);
        t.resourceCountIs("AWS::SQS::Queue", 2);
        t.resourceCountIs("AWS::Events::Rule", 1);
        t.allResourcesProperties("AWS::Lambda::Function", Map.of("Runtime", "java21", "Timeout", 30, "MemorySize", 1024));
        t.hasOutput("TemplateEngine", Map.of("Value", "JavaCDK-v2"));
    }

    @Test void queueRoutingAndRetry() {
        Template t = template();
        t.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "ecommerce-test-orders",
                "VisibilityTimeout", 180, "SqsManagedSseEnabled", true,
                "RedrivePolicy", Match.objectLike(Map.of("maxReceiveCount", 5))));
        t.hasResourceProperties("AWS::SQS::Queue", Map.of("QueueName", "ecommerce-test-orders-dlq", "MessageRetentionPeriod", 1209600));
        t.hasResourceProperties("AWS::Events::Rule", Map.of("EventPattern", Map.of(
                "source", List.of("com.ecommerce.basket.checkout"), "detail-type", List.of("CheckoutBasket")),
                "Targets", Match.arrayWith(List.of(Match.objectLike(Map.of("Arn", Match.anyValue()))))));
        t.hasResourceProperties("AWS::Lambda::EventSourceMapping", Map.of("BatchSize", 1));
        t.hasResourceProperties("AWS::SQS::QueuePolicy", Map.of("PolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(List.of(Match.objectLike(Map.of("Action", Match.arrayWith(List.of("sqs:SendMessage")),
                        "Principal", Map.of("Service", "events.amazonaws.com"), "Condition", Match.anyValue()))))))));
    }

    @Test void retainsDataAndSchema() {
        Template t = template();
        t.allResources("AWS::DynamoDB::Table", Map.of("DeletionPolicy", "Retain", "UpdateReplacePolicy", "Retain"));
        t.hasResourceProperties("AWS::DynamoDB::Table", Map.of("TableName", "ecommerce-test-orders",
                "BillingMode", "PAY_PER_REQUEST", "KeySchema", List.of(Map.of("AttributeName", "userName", "KeyType", "HASH"),
                        Map.of("AttributeName", "orderDate", "KeyType", "RANGE"))));
    }

    @Test void iamIsolationAndEnvironment() {
        Template t = template();
        var policies = t.findResources("AWS::IAM::Policy");
        String readPolicy = policies.entrySet().stream().filter(e -> e.getKey().startsWith("OrderApiFunction"))
                .findFirst().orElseThrow().getValue().toString();
        assertTrue(readPolicy.contains("dynamodb:GetItem"));
        assertFalse(readPolicy.contains("dynamodb:PutItem"));
        String productPolicy = policies.entrySet().stream().filter(e -> e.getKey().startsWith("ProductFunction"))
                .findFirst().orElseThrow().getValue().toString();
        assertFalse(productPolicy.contains("events:PutEvents"));
        assertFalse(productPolicy.contains("sqs:ReceiveMessage"));
        t.hasResourceProperties("AWS::Lambda::Function", Map.of("Handler", "com.ecommerce.basket.BasketHandler::handleRequest",
                "Environment", Match.objectLike(Map.of("Variables", Match.objectLike(Map.of(
                        "EVENT_SOURCE", "com.ecommerce.basket.checkout", "EVENT_DETAILTYPE", "CheckoutBasket"))))));
    }

    @Test void apiContractsAndLogs() {
        Template t = template();
        t.allResourcesProperties("AWS::ApiGateway::Method", Map.of("AuthorizationType", "NONE",
                "Integration", Match.objectLike(Map.of("Type", "AWS_PROXY", "IntegrationHttpMethod", "POST"))));
        t.allResourcesProperties("AWS::ApiGateway::Stage", Map.of("StageName", "prod"));
        t.resourceCountIs("AWS::Logs::LogGroup", 4);
        t.allResourcesProperties("AWS::Logs::LogGroup", Map.of("RetentionInDays", 7));
        for (String api : List.of("ProductApi", "BasketApi", "OrderApi")) {
            t.hasOutput(api + "Url", Map.of("Value", Match.anyValue()));
        }
    }

    @Test void rejectsInvalidStage() {
        assertThrows(IllegalArgumentException.class, () -> new EcommerceStack(new App(), "Invalid",
                StackProps.builder().build(), "../prod", null));
    }
}
