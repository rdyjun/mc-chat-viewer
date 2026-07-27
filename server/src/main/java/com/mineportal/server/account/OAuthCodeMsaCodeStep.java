package com.mineportal.server.account;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.step.AbstractStep;
import net.raphimc.minecraftauth.step.msa.MsaCodeStep;
import net.raphimc.minecraftauth.util.logging.ILogger;

/**
 * Bridges our own Spring MVC OAuth redirect handling (our servlet already owns the
 * /api/account/callback endpoint and receives the authorization code directly) into
 * MinecraftAuth's step chain, standing in for its built-in device-code or embedded-webserver
 * first steps. This step does no network I/O itself — it just wraps the code we already
 * received so StepMsaToken (further down the chain) can exchange it the same way it would for
 * any other login method.
 */
public class OAuthCodeMsaCodeStep extends MsaCodeStep<OAuthCodeMsaCodeStep.CodeInput> {

    public OAuthCodeMsaCodeStep(final AbstractStep.ApplicationDetails applicationDetails) {
        super(applicationDetails);
    }

    @Override
    protected MsaCode execute(final ILogger logger, final HttpClient httpClient, final CodeInput input) {
        return new MsaCode(input.code());
    }

    public static final class CodeInput extends AbstractStep.InitialInput {
        private final String code;

        public CodeInput(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

}
