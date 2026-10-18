package io.contractforge.architecturefixtures.application;

import io.contractforge.architecturefixtures.infrastructure.ForbiddenInfrastructureFixture;

public class ForbiddenApplicationFixture {
    private final ForbiddenInfrastructureFixture dependency = new ForbiddenInfrastructureFixture();

    public ForbiddenInfrastructureFixture dependency() {
        return dependency;
    }
}
