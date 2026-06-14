const { DynamoDBClient, DeleteItemCommand, GetItemCommand, PutItemCommand, ScanCommand } = require("@aws-sdk/client-dynamodb");
const { EventBridgeClient, PutEventsCommand } = require("@aws-sdk/client-eventbridge");
const { marshall, unmarshall } = require("@aws-sdk/util-dynamodb");

const dynamodb = new DynamoDBClient({});
const eventbridge = new EventBridgeClient({});
const tableName = process.env.DYNAMODB_TABLE_NAME;
const primaryKey = process.env.PRIMARY_KEY || "userName";

const response = (statusCode, body) => ({
  statusCode,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const parseBody = (event) => {
  if (!event.body) {
    return {};
  }
  return typeof event.body === "string" ? JSON.parse(event.body) : event.body;
};

const getBasket = async (userName) => {
  const result = await dynamodb.send(new GetItemCommand({
    TableName: tableName,
    Key: marshall({ [primaryKey]: userName }),
  }));

  return result.Item ? unmarshall(result.Item) : undefined;
};

exports.handler = async (event) => {
  try {
    const method = event.httpMethod;
    const userName = event.pathParameters && event.pathParameters.userName;
    const path = event.resource || event.path || "";

    if (method === "GET" && userName) {
      const basket = await getBasket(userName);
      return basket ? response(200, basket) : response(404, { message: "Basket not found" });
    }

    if (method === "GET") {
      const result = await dynamodb.send(new ScanCommand({ TableName: tableName }));
      return response(200, (result.Items || []).map((item) => unmarshall(item)));
    }

    if (method === "POST" && path.endsWith("/checkout")) {
      const body = parseBody(event);
      const checkoutUserName = body.userName || body.username;

      if (!checkoutUserName) {
        return response(400, { message: "userName is required" });
      }

      const basket = await getBasket(checkoutUserName);
      const detail = {
        ...body,
        userName: checkoutUserName,
        basket,
      };

      await eventbridge.send(new PutEventsCommand({
        Entries: [{
          Source: process.env.EVENT_SOURCE,
          DetailType: process.env.EVENT_DETAILTYPE,
          EventBusName: process.env.EVENT_BUSNAME,
          Detail: JSON.stringify(detail),
        }],
      }));

      return response(202, { message: "Checkout event published", detail });
    }

    if (method === "POST") {
      const body = parseBody(event);
      const basketUserName = body.userName || body.username;

      if (!basketUserName) {
        return response(400, { message: "userName is required" });
      }

      const item = { ...body, userName: basketUserName };
      await dynamodb.send(new PutItemCommand({
        TableName: tableName,
        Item: marshall(item, { removeUndefinedValues: true }),
      }));

      return response(201, item);
    }

    if (method === "DELETE" && userName) {
      await dynamodb.send(new DeleteItemCommand({
        TableName: tableName,
        Key: marshall({ [primaryKey]: userName }),
      }));

      return response(204, {});
    }

    return response(405, { message: `Unsupported route: ${method} ${path}` });
  } catch (error) {
    console.error(error);
    return response(500, { message: "Internal server error", error: error.message });
  }
};
