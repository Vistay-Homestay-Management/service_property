package com.vistay.property.adapter.in.graphql;

import com.vistay.property.application.DynamicConfigException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
public class PropertyGraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable exception, DataFetchingEnvironment environment) {
        if (exception instanceof DynamicConfigException domain) {
            return GraphqlErrorBuilder.newError(environment).message(domain.getMessage())
                .errorType(domain.code().startsWith("FORBIDDEN") ? ErrorType.FORBIDDEN : ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", domain.code())).build();
        }
        return null;
    }
}
