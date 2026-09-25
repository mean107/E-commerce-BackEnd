package com.ecommerce.infra;

import java.nio.file.Files;
import java.nio.file.Path;
import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.lambda.Code;

/** CDK CLI runs this Java entry point through Maven from the repository root. */
public final class EcommerceApp {
    public static void main(String[] args) {
        App app = new App();
        Object context = app.getNode().tryGetContext("stage");
        String stage = context == null ? "dev" : context.toString();
        Path jar = Path.of("services/target/ecommerce-services.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Missing Lambda JAR. Run cdk synth from repository root; cdk.json builds it automatically.");
        }
        // Environment-agnostic: CLI selects account/region from the active AWS profile.
        new EcommerceStack(app, "EcommerceJava-" + stage, StackProps.builder().build(),
                stage, Code.fromAsset(jar.toString()));
        app.synth();
    }
}
