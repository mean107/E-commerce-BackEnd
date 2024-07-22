import { Construct } from "constructs";
import * as cdk from "aws-cdk-lib";
import {
  AttributeType,
  BillingMode,
  ITable,
  Table,
} from "aws-cdk-lib/aws-dynamodb";

export class SwnDatabase extends Construct {
  public readonly productTable: ITable;
  public readonly basketTable: ITable;
  public readonly orderTable: ITable;

  constructor(scope: Construct, id: string) {
    super(scope, id);

    // product table
    this.productTable = this.createProductTable();
    // basket table
    this.basketTable = this.createBasketTable();
    // order table
    this.orderTable = this.createOrderTable();
  }

  private createProductTable(): ITable {
    // Product DynamoDB Table Creation
    // product : PK : id -- name - description - imageFile - price - category
    const productTable = new Table(this, "product", {
      partitionKey: {
        name: "id",
        type: AttributeType.STRING,
      },
      tableName: "mean_product",
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      billingMode: BillingMode.PAY_PER_REQUEST,
    });
    return productTable;
  }

  private createBasketTable(): ITable {
    // Basket DynamoDB Table Creation
    // basket : PK : username -- items (SET-MAP object)
    // item1 - { quantity - color - price - productId - productName }
    // item2 - { quantity - color - price - productId - productName }
    const basketTable = new Table(this, "basket", {
      partitionKey: {
        name: "userName",
        type: AttributeType.STRING,
      },
      tableName: "mean_basket",
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      billingMode: BillingMode.PAY_PER_REQUEST,
    });
    return basketTable;
  }

  private createOrderTable(): ITable {
    // Order DynamoDB Table Creation
    // order : PK : userName - SK : orderDate --totalPrice - firstName - lastName - email - address - paymentMethod - cardInfo
    const orderTable = new Table(this, "order", {
      partitionKey: {
        name: "username",
        type: AttributeType.STRING,
      },
      sortKey: {
        name: "orderDate",
        type: AttributeType.STRING,
      },
      tableName: "mean_order",
      removalPolicy: cdk.RemovalPolicy.DESTROY,
      billingMode: BillingMode.PAY_PER_REQUEST,
    });
    return orderTable;
  }
}
