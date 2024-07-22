import { LambdaRestApi } from "aws-cdk-lib/aws-apigateway";
import { Construct } from "constructs";
import { IFunction } from "aws-cdk-lib/aws-lambda";

interface SwnApiGatewayProps {
  productMicroservice: IFunction;
  basketMicroservice: IFunction;
  orderingMicroservice: IFunction;
}

export class SwnApiGateway extends Construct {
  constructor(scope: Construct, id: string, props: SwnApiGatewayProps) {
    super(scope, id);

    // product api gateway
    this.createProductApi(props.productMicroservice);
    // basket api gateway
    this.createBasketApi(props.basketMicroservice);
    // order api gateway
    this.createOrderApi(props.orderingMicroservice);
  }
  private createProductApi(productMicroservice: IFunction) {
    // Product microservices api gateway
    // root name = product

    // GET /product
    // POST /product

    // Single product with id parameter
    // GET /product/{id}
    // PUT /product/{id}
    // DELETE /product/{id}

    const apigw = new LambdaRestApi(this, "productApi", {
      restApiName: "Product Service",
      handler: productMicroservice,
      proxy: false,
    });

    const product = apigw.root.addResource("product");
    product.addMethod("GET");
    product.addMethod("POST");

    const singleProduct = product.addResource("{id}");
    singleProduct.addMethod("GET");
    singleProduct.addMethod("PUT");
    singleProduct.addMethod("DELETE");
  }
  private createBasketApi(basketMicroservice: IFunction) {
    // Basket microservices api gateway
    // root name = basket

    // GET /basket
    // POST /basket

    // resource name = basket/{userName}

    // GET /basket/{userName}
    // DELETE /basket/{userName}

    // POST /basket/{checkout}

    const apigw = new LambdaRestApi(this, "basketApi", {
      restApiName: "Basket Service",
      handler: basketMicroservice,
      proxy: false,
    });

    const basket = apigw.root.addResource("basket");
    basket.addMethod("GET");
    basket.addMethod("POST");

    const singleBasket = basket.addResource("{userName}");
    singleBasket.addMethod("GET");
    singleBasket.addMethod("DELETE");

    const basketCheckout = basket.addResource("checkout");
    basketCheckout.addMethod("POST");
  }
  private createOrderApi(orderMicroservice: IFunction) {
    // Ordering microservice api gateway
    // root name = order
    const apigw = new LambdaRestApi(this, "orderApi", {
      restApiName: "Order Service",
      handler: orderMicroservice,
      proxy: false,
    });

    const order = apigw.root.addResource("order");
    order.addMethod("GET");

    const singleOrder = order.addResource("{userName}");
    singleOrder.addMethod("GET");
    return singleOrder;
  }
}
