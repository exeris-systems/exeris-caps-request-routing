package eu.exeris.caps.routing;

import eu.exeris.caps.routing.api.RequestRouter;
import eu.exeris.sdk.annotation.capability.CapabilityModule;
import eu.exeris.sdk.annotation.capability.Provides;

/**
 * The capability declaration for {@code exeris-caps-request-routing}.
 *
 * <p>Carries the {@code @CapabilityModule} and {@code @Provides} contract annotations
 * for the build-time codegen pipeline and composition conductor.
 *
 * @since 0.1.0
 */
@CapabilityModule
@Provides(service = RequestRouter.class, version = "0.1.0")
public final class RequestRoutingModule {

    private RequestRoutingModule() {
        // Declaration carrier only — never constructed.
    }
}
