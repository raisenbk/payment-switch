package com.paymentswitch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** docs/openapi.yaml documents exactly the endpoints the application serves: no more, no less. */
class OpenApiSpecTest extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void specMatchesTheControllers() throws IOException {
        Set<String> served = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPathPatternsCondition().getPatternValues()
                .stream()
                .filter(path -> !path.startsWith("/error") && !path.startsWith("/v1/test/"))
                .forEach(path -> info.getMethodsCondition().getMethods()
                        .forEach(method -> served.add(method.name() + " " + path))));

        assertThat(documented()).isEqualTo(served);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documented() throws IOException {
        Path spec = Path.of("..", "docs", "openapi.yaml");
        Map<String, Object> root;
        try (Reader reader = Files.newBufferedReader(spec)) {
            root = new Yaml().load(reader);
        }
        Set<String> documented = new TreeSet<>();
        ((Map<String, Map<String, Object>>) root.get("paths")).forEach((path, operations) ->
                operations.keySet().stream()
                        .filter(key -> Set.of("get", "post", "put", "patch", "delete").contains(key))
                        .forEach(method -> documented.add(method.toUpperCase() + " " + path)));
        return documented;
    }
}
