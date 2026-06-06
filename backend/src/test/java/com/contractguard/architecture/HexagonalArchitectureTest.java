package com.contractguard.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces the hexagonal layering mandated by the requirements: domain is
 * framework-free, application depends only on domain and its own ports,
 * adapters implement ports, and the web adapter reaches the rest of the
 * system exclusively through application services.
 */
@AnalyzeClasses(packages = "com.contractguard", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    private static final String DOMAIN = "com.contractguard.domain..";
    private static final String APPLICATION = "com.contractguard.application..";
    private static final String ADAPTER = "com.contractguard.adapter..";
    private static final String CONFIG = "com.contractguard.config..";

    @ArchTest
    static final ArchRule domainDependsOnlyOnJdk = classes()
            .that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(DOMAIN, "java..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomainAndJdk = classes()
            .that().resideInAPackage(APPLICATION)
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(APPLICATION, DOMAIN, "java..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule adaptersDoNotDependOnEachOther = noClasses()
            .that().resideInAPackage("com.contractguard.adapter.web..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.contractguard.adapter.diff..",
                    "com.contractguard.adapter.search..",
                    "com.contractguard.adapter.git..",
                    "com.contractguard.adapter.process..",
                    "com.contractguard.adapter.llm..",
                    "com.contractguard.adapter.persistence..",
                    "com.contractguard.adapter.artifacts..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule webAdapterUsesApplicationServicesNotDomainServices = noClasses()
            .that().resideInAPackage("com.contractguard.adapter.web..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.contractguard.application.port..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainIsFreeOfFrameworkAnnotations = noClasses()
            .that().resideInAnyPackage(DOMAIN, APPLICATION)
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.fasterxml.jackson..",
                    "io.swagger..",
                    "com.github.difflib..",
                    "org.eclipse.jgit..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyConfigAndAdaptersTouchSpring = noClasses()
            .that().resideOutsideOfPackages(ADAPTER, CONFIG, "com.contractguard")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..")
            .allowEmptyShould(true);
}
