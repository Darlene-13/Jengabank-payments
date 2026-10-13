package com.jengabank.payments.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;

/** Describes the API at /openapi.json (and in Swagger UI when it is switched on). */
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearer";

    @Bean
    public OpenAPI jengaBankOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JengaBank Payments API")
                        .version("v1")
                        .description("""
                                Double-entry KES payments with maker-checker approvals, fraud and limit controls, \
                                append-only ledgers and a hash-chained audit trail.

                                Money is always an integer in minor units (amountMinor 125050 = KES 1,250.50). \
                                Every request that moves money needs an Idempotency-Key header (a UUID). \
                                Errors share one shape: {code, message, requestId, reasons}. \
                                Full guide: docs/api.md."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .description("Opaque token from POST /api/v1/auth/login. Expires after 30 minutes.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .addTagsItem(new Tag().name("Auth").description("Sign in, sign out, current user"))
                .addTagsItem(new Tag().name("Accounts").description("CUSTOMER: own accounts, live balances, statements"))
                .addTagsItem(new Tag().name("Transfers").description("Send money; customers see their own, staff see all"))
                .addTagsItem(new Tag().name("Approvals").description("CHECKER: decide transfers held for a second person"))
                .addTagsItem(new Tag().name("Reversals").description("MAKER requests, CHECKER approves at KES 250,000 and above"))
                .addTagsItem(new Tag().name("Audit").description("MAKER, CHECKER: hash-chained audit trail"))
                .addTagsItem(new Tag().name("Reconciliation").description("Independent proofs that the books balance"))
                .addTagsItem(new Tag().name("Health").description("Liveness and readiness"));
    }
}
