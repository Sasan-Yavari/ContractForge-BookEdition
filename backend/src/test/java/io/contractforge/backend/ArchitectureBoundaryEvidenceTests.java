package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import io.contractforge.architecturefixtures.application.ForbiddenApplicationFixture;
import io.contractforge.architecturefixtures.infrastructure.ForbiddenInfrastructureFixture;
import org.junit.jupiter.api.Test;

class ArchitectureBoundaryEvidenceTests {

    @Test
    void boundaryRuleFailsForAControlledForbiddenDependency() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                ForbiddenApplicationFixture.class, ForbiddenInfrastructureFixture.class);
        ArchRule rule = noClasses().that().resideInAnyPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

        assertThatThrownBy(() -> rule.check(fixture)).isInstanceOf(AssertionError.class);
    }
}
