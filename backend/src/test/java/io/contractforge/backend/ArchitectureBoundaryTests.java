package io.contractforge.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.contractforge.backend")
class ArchitectureBoundaryTests {

    @ArchTest
    static final ArchRule applicationAndDomainDoNotDependOnInfrastructure = noClasses()
            .that().resideInAnyPackage("..application..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAnyPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..");

    @ArchTest
    static final ArchRule applicationAndDomainDoNotDependOnDatabaseDetails = noClasses()
            .that().resideInAnyPackage("..application..", "..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "org.postgresql..");

    @ArchTest
    static final ArchRule applicationDomainAndApiDoNotDependOnAwsSdk = noClasses()
            .that().resideInAnyPackage("..application..", "..domain..", "..api..")
            .should().dependOnClassesThat().resideInAnyPackage("software.amazon.awssdk..");
}
