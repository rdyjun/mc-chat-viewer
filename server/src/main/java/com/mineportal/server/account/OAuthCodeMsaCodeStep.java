package com.mineportal.server.account;

import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.step.AbstractStep;
import net.raphimc.minecraftauth.step.msa.MsaCodeStep;
import net.raphimc.minecraftauth.util.logging.ILogger;

/**
 * 우리 자체의 Spring MVC OAuth 리다이렉트 처리(이미 우리 서블릿이 /api/account/callback
 * 엔드포인트를 소유하고 authorization code를 직접 받는다)를 MinecraftAuth의 스텝 체인에
 * 연결해준다. 즉 MinecraftAuth에 내장된 device-code나 내장 웹서버 방식의 첫 단계를
 * 대신하는 역할이다. 이 스텝 자체는 네트워크 I/O를 전혀 하지 않는다 — 이미 받아둔 code를
 * 그대로 감싸서, 체인의 다음 단계인 StepMsaToken이 다른 로그인 방식과 동일한 방식으로
 * 이를 교환할 수 있게 할 뿐이다.
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
