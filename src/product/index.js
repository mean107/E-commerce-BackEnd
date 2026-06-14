const { DynamoDBClient, DeleteItemCommand, GetItemCommand, PutItemCommand, ScanCommand } = require("@aws-sdk/client-dynamodb");
const { marshall, unmarshall } = require("@aws-sdk/util-dynamodb");
const { v4: uuidv4 } = require("uuid");

const client = new DynamoDBClient({});
const tableName = process.env.DYNAMODB_TABLE_NAME;
const primaryKey = process.env.PRIMARY_KEY || "id";

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

exports.handler = async (event) => {
  try {
    const method = event.httpMethod;
    const id = event.pathParameters && event.pathParameters.id;

    if (method === "GET" && id) {
      const result = await client.send(new GetItemCommand({
        TableName: tableName,
        Key: marshall({ [primaryKey]: id }),
      }));

      if (!result.Item) {
        return response(404, { message: "Product not found" });
      }

      return response(200, unmarshall(result.Item));
    }

    if (method === "GET") {
      const result = await client.send(new ScanCommand({ TableName: tableName }));
      return response(200, (result.Items || []).map((item) => unmarshall(item)));
    }

    if (method === "POST") {
      const body = parseBody(event);
      const item = { ...body, [primaryKey]: body[primaryKey] || uuidv4() };

      await client.send(new PutItemCommand({
        TableName: tableName,
        Item: marshall(item, { removeUndefinedValues: true }),
      }));

      return response(201, item);
    }

    if (method === "PUT" && id) {
      const body = parseBody(event);
      const item = { ...body, [primaryKey]: id };

      await client.send(new PutItemCommand({
        TableName: tableName,
        Item: marshall(item, { removeUndefinedValues: true }),
      }));

      return response(200, item);
    }

    if (method === "DELETE" && id) {
      await client.send(new DeleteItemCommand({
        TableName: tableName,
        Key: marshall({ [primaryKey]: id }),
      }));

      return response(204, {});
    }

    return response(405, { message: `Unsupported route: ${method} ${event.resource || event.path}` });
  } catch (error) {
    console.error(error);
    return response(500, { message: "Internal server error", error: error.message });
  }
};
