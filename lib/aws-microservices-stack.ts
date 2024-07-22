import * as cdk from "aws-cdk-lib";
import { Construct } from "constructs";
import { SwnDatabase } from "./database";
import { SwnMicroservices } from "./microservice";
import { SwnApiGateway } from "./apigateway";
import { SwnEventBus } from "./eventbus";
import { SwnQueue } from "./queue";

export class AwsMicroservicesStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const database = new SwnDatabase(this, "Database");

    const microservices = new SwnMicroservices(this, "Microservices", {
      productTable: database.productTable,
      basketTable: database.basketTable,
      orderTable: database.orderTable,
    });

    const apigateway = new SwnApiGateway(this, "Apigateway", {
      productMicroservice: microservices.productMicroservice,
      basketMicroservice: microservices.basketMicroservice,
      orderingMicroservice: microservices.orderingMicroservice,
    });

    const queue = new SwnQueue(this, "OrderQueue", {
      consummer: microservices.orderingMicroservice,
    });

    const eventbus = new SwnEventBus(this, "SwnEventBus", {
      publisherFunction: microservices.basketMicroservice,
      targetQueue: queue.orderQueue,
    });
  }
}
