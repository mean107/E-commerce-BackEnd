const { DynamoDBClient, GetItemCommand, PutItemCommand, QueryCommand, ScanCommand } = require("@aws-sdk/client-dynamodb");
const { marshall, unmarshall } = require("@aws-sdk/util-dynamodb");

const client = new DynamoDBClient({});
const tableName = process.env.DYNAMODB_TABLE_NAME;
const primaryKey = process.env.PRIMARY_KEY || "userName";
const sortKey = process.env.SORT_KEY || "orderDate";

const response = (statusCode, body) => ({
  statusCode,
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(body),
});

const parseBody = (body) => {
  if (!body) {
    return {};
  }
  return typeof body === "string" ? JSON.parse(body) : body;
};

const normalizeOrder = (detail) => {
  const userName = detail.userName || detail.username;

  if (!userName) {
    throw new Error("userName is required");
  }

  return {
    ...detail,
    userName,
    [sortKey]: detail[sortKey] || new Date().toISOString(),
  };
};

const saveOrder = async (order) => {
  await client.send(new PutItemCommand({
    TableName: tableName,
    Item: marshall(order, { removeUndefinedValues: true }),
  }));
};

const handleSqsEvent = async (event) => {
  const savedOrders = [];

  for (const record of event.Records || []) {
    const message = parseBody(record.body);
    const detail = parseBody(message.detail || message.Detail || message);
    const order = normalizeOrder(detail);

    await saveOrder(order);
    savedOrders.push(order);
  }

  return { savedOrders };
};

exports.handler = async (event) => {
  try {
    if (event.Records) {
      return await handleSqsEvent(event);
    }

    const method = event.httpMethod;
    const userName = event.pathParameters && event.pathParameters.userName;

    if (method === "GET" && userName) {
      const orderDate = event.queryStringParameters && event.queryStringParameters.orderDate;

      if (orderDate) {
        const result = await client.send(new GetItemCommand({
          TableName: tableName,
          Key: marshall({ [primaryKey]: userName, [sortKey]: orderDate }),
        }));

        return result.Item ? response(200, unmarshall(result.Item)) : response(404, { message: "Order not found" });
      }

      const result = await client.send(new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: "#pk = :pk",
        ExpressionAttributeNames: { "#pk": primaryKey },
        ExpressionAttributeValues: marshall({ ":pk": userName }),
      }));

      return response(200, (result.Items || []).map((item) => unmarshall(item)));
    }

    if (method === "GET") {
      const result = await client.send(new ScanCommand({ TableName: tableName }));
      return response(200, (result.Items || []).map((item) => unmarshall(item)));
    }

    return response(405, { message: `Unsupported route: ${method} ${event.resource || event.path}` });
  } catch (error) {
    console.error(error);
    return response(500, { message: "Internal server error", error: error.message });
  }
};
