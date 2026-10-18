package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.sql.SQLException;
import org.postgresql.util.PGobject;

final class JdbcJsonb {

    private JdbcJsonb() {
    }

    static PGobject value(JsonValue json) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(json.value());
            return object;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create JSONB value", exception);
        }
    }

    static JsonValue read(Object value) {
        return new JsonValue(value.toString());
    }
}
