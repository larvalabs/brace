package io.brace;

import gg.jte.ContentType;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;

import java.nio.file.Path;
import java.util.Map;

public class TemplateEngine {

    private final gg.jte.TemplateEngine engine;

    public TemplateEngine(String templatePath) {
        var codeResolver = new DirectoryCodeResolver(Path.of(templatePath));
        this.engine = gg.jte.TemplateEngine.create(codeResolver, ContentType.Html);
    }

    public String render(String template, Map<String, Object> params) {
        var output = new StringOutput();
        engine.render(template + ".jte", params, output);
        return output.toString();
    }
}
