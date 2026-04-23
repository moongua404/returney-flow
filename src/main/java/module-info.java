module flow.core {
    requires java.net.http;
    requires org.yaml.snakeyaml;
    requires com.google.gson;

    exports com.returney.flow.port;
    exports com.returney.flow.domain.definition;
    exports com.returney.flow.domain.execution;
    exports com.returney.flow.domain.llm;
    exports com.returney.flow.adapter.parser;
}
