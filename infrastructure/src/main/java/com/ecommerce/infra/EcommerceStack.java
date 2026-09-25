package com.ecommerce.infra;

import java.util.List;
import java.util.Map;
import software.constructs.Construct;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.apigateway.*;
import software.amazon.awscdk.services.dynamodb.*;
import software.amazon.awscdk.services.events.*;
import software.amazon.awscdk.services.events.targets.SqsQueue;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.*;
import software.amazon.awscdk.services.sqs.*;

/** Java CDK constructs declare the complete application infrastructure. */
public final class EcommerceStack extends Stack {
    private final String prefix;

    public EcommerceStack(Construct scope, String id, StackProps props, String stage, Code code) {
        super(scope, id, props);
        if (!stage.matches("[a-z][a-z0-9-]{0,19}")) throw new IllegalArgumentException("Invalid stage");
        prefix = "ecommerce-" + stage;
        Tags.of(this).add("Project", "ecommerce");
        Tags.of(this).add("Stage", stage);
        Table products = table("Products", "products", "id", null);
        Table baskets = table("Baskets", "baskets", "userName", null);
        Table orders = table("Orders", "orders", "userName", "orderDate");
        EventBus bus = EventBus.Builder.create(this, "EventBus").eventBusName(prefix + "-events").build();
        Queue dlq = Queue.Builder.create(this, "OrderDeadLetterQueue")
                .queueName(prefix + "-orders-dlq").retentionPeriod(Duration.days(14))
                .encryption(QueueEncryption.SQS_MANAGED).enforceSsl(true).build();
        Queue queue = Queue.Builder.create(this, "OrderQueue").queueName(prefix + "-orders")
                .visibilityTimeout(Duration.seconds(180)).encryption(QueueEncryption.SQS_MANAGED)
                .enforceSsl(true).deadLetterQueue(DeadLetterQueue.builder().queue(dlq).maxReceiveCount(5).build()).build();
        Rule rule = Rule.Builder.create(this, "CheckoutRule").ruleName(prefix + "-checkout").eventBus(bus)
                .eventPattern(EventPattern.builder().source(List.of("com.ecommerce.basket.checkout"))
                        .detailType(List.of("CheckoutBasket")).build()).build();
        rule.addTarget(SqsQueue.Builder.create(queue).build());
        Function product = function("ProductFunction", "product.ProductHandler", products, code, true, true);
        Function basket = function("BasketFunction", "basket.BasketHandler", baskets, code, true, true);
        Function orderApi = function("OrderApiFunction", "ordering.OrderApiHandler", orders, code, true, false);
        Function orderQueue = function("OrderQueueFunction", "ordering.OrderQueueHandler", orders, code, false, true);
        basket.addEnvironment("EVENT_BUSNAME", bus.getEventBusName());
        basket.addEnvironment("EVENT_SOURCE", "com.ecommerce.basket.checkout");
        basket.addEnvironment("EVENT_DETAILTYPE", "CheckoutBasket");
        bus.grantPutEventsTo(basket);
        orderQueue.addEventSource(SqsEventSource.Builder.create(queue).batchSize(1).build());
        api("ProductApi", "product", product, List.of(new Route("", "GET"), new Route("", "POST"),
                new Route("{id}", "GET"), new Route("{id}", "PUT"), new Route("{id}", "DELETE")));
        api("BasketApi", "basket", basket, List.of(new Route("", "GET"), new Route("", "POST"),
                new Route("{userName}", "GET"), new Route("{userName}", "DELETE"), new Route("checkout", "POST")));
        api("OrderApi", "order", orderApi, List.of(new Route("", "GET"), new Route("{userName}", "GET")));
        output("OrderQueueUrl", queue.getQueueUrl());
        output("OrderDeadLetterQueueUrl", dlq.getQueueUrl());
        output("EventBusName", bus.getEventBusName());
        output("TemplateEngine", "JavaCDK-v2");
    }

    private Table table(String id, String suffix, String pk, String sk) {
        var builder = Table.Builder.create(this, id).tableName(prefix + "-" + suffix)
                .partitionKey(Attribute.builder().name(pk).type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST).removalPolicy(RemovalPolicy.RETAIN);
        if (sk != null) builder.sortKey(Attribute.builder().name(sk).type(AttributeType.STRING).build());
        return builder.build();
    }

    private Function function(String id, String handler, Table table, Code code, boolean read, boolean write) {
        String name = prefix + "-" + id;
        LogGroup logs = LogGroup.Builder.create(this, id + "Logs").logGroupName("/aws/lambda/" + name)
                .retention(RetentionDays.ONE_WEEK).removalPolicy(RemovalPolicy.DESTROY).build();
        Function fn = Function.Builder.create(this, id).functionName(name).runtime(Runtime.JAVA_21)
                .handler("com.ecommerce." + handler + "::handleRequest").code(code)
                .memorySize(1024).timeout(Duration.seconds(30)).logGroup(logs)
                .environment(Map.of("DYNAMODB_TABLE_NAME", table.getTableName())).build();
        var actions = new java.util.ArrayList<String>();
        if (read) actions.addAll(List.of("dynamodb:GetItem", "dynamodb:Scan", "dynamodb:Query"));
        if (write) actions.addAll(List.of("dynamodb:PutItem", "dynamodb:DeleteItem"));
        fn.addToRolePolicy(PolicyStatement.Builder.create().actions(actions).resources(List.of(table.getTableArn())).build());
        return fn;
    }

    private record Route(String child, String method) {}

    private void api(String id, String rootPath, Function handler, List<Route> routes) {
        RestApi api = RestApi.Builder.create(this, id).restApiName(prefix + "-" + rootPath + "-api")
                .endpointTypes(List.of(EndpointType.REGIONAL)).cloudWatchRole(false)
                .deployOptions(StageOptions.builder().stageName("prod").build()).build();
        Resource root = api.getRoot().addResource(rootPath);
        var children = new java.util.HashMap<String, Resource>();
        LambdaIntegration integration = new LambdaIntegration(handler,
                LambdaIntegrationOptions.builder().allowTestInvoke(false).build());
        for (Route route : routes) {
            Resource resource = route.child().isEmpty() ? root : children.computeIfAbsent(route.child(), root::addResource);
            resource.addMethod(route.method(), integration);
        }
        output(id + "Url", api.getUrl());
    }

    private void output(String id, String value) { CfnOutput.Builder.create(this, id).value(value).build(); }
}
