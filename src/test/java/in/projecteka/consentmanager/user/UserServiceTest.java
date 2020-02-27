package in.projecteka.consentmanager.user;

import in.projecteka.consentmanager.AuthorizationTest;
import in.projecteka.consentmanager.clients.IdentityServiceClient;
import in.projecteka.consentmanager.clients.OtpServiceClient;
import in.projecteka.consentmanager.clients.model.OtpRequest;
import in.projecteka.consentmanager.clients.properties.OtpServiceProperties;
import in.projecteka.consentmanager.user.exception.InvalidRequestException;
import in.projecteka.consentmanager.clients.model.KeycloakToken;
import in.projecteka.consentmanager.user.model.OtpVerification;
import in.projecteka.consentmanager.user.model.SignUpSession;
import in.projecteka.consentmanager.user.model.Token;
import in.projecteka.consentmanager.user.model.UserSignUpEnquiry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.stream.Stream;

import static in.projecteka.consentmanager.user.TestBuilders.keycloakToken;
import static in.projecteka.consentmanager.user.TestBuilders.signUpRequest;
import static in.projecteka.consentmanager.user.TestBuilders.string;
import static in.projecteka.consentmanager.user.TestBuilders.userSignUpEnquiry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class UserServiceTest {

    @Captor
    private ArgumentCaptor<OtpRequest> otpRequestArgumentCaptor;

    @Captor
    private ArgumentCaptor<String> sessionCaptor;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OtpServiceClient otpServiceClient;

    @Mock
    private UserVerificationService userVerificationService;

    @Mock
    private IdentityServiceClient identityServiceClient;

    @Mock
    private TokenService tokenService;

    private UserService userService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        OtpServiceProperties otpServiceProperties = new OtpServiceProperties(
                "",
                Collections.singletonList("MOBILE"));
        userService = new UserService(
                userRepository,
                otpServiceProperties,
                otpServiceClient,
                userVerificationService,
                identityServiceClient,
                tokenService);
    }

    @ParameterizedTest
    @MethodSource("mobileNumberProvider")
    public void shouldReturnTemporarySessionReceivedFromClient(
            AbstractMap.SimpleEntry<String, String> mobileNumber) {
        var userSignUpEnquiry = new UserSignUpEnquiry("MOBILE", mobileNumber.getKey());
        var sessionId = string();
        var signUpSession = new SignUpSession(sessionId);
        when(otpServiceClient.send(otpRequestArgumentCaptor.capture())).thenReturn(Mono.empty());
        when(userVerificationService.cacheAndSendSession(sessionCaptor.capture(), eq(mobileNumber.getValue())))
                .thenReturn(signUpSession);

        Mono<SignUpSession> signUp = userService.sendOtp(userSignUpEnquiry);

        assertThat(otpRequestArgumentCaptor.getValue().getSessionId()).isEqualTo(sessionCaptor.getValue());
        StepVerifier.create(signUp)
                .assertNext(session -> assertThat(session).isEqualTo(signUpSession))
                .verifyComplete();
    }

    @Test
    public void shouldThrowInvalidRequestExceptionForInvalidDeviceType() {
        Assertions.assertThrows(InvalidRequestException.class, () -> userService.sendOtp(userSignUpEnquiry().build()));
    }

    @Test
    public void shouldReturnTokenReceivedFromClient() {
        var sessionId = string();
        var otp = string();
        var token = string();
        OtpVerification otpVerification = new OtpVerification(sessionId, otp);
        when(otpServiceClient.verify(sessionId, otp)).thenReturn(Mono.empty());
        when(userVerificationService.generateToken(sessionId))
                .thenReturn(new Token(token));

        StepVerifier.create(userService.permitOtp(otpVerification))
                .assertNext(response -> assertThat(response.getTemporaryToken()).isEqualTo(token))
                .verifyComplete();
    }

    @ParameterizedTest(name = "Invalid values")
    @CsvSource({
            ",",
            "empty",
            "null"
    })
    public void shouldThrowInvalidRequestExceptionForInvalidOtpValue(
            @ConvertWith(AuthorizationTest.NullableConverter.class) String value) {
        OtpVerification otpVerification = new OtpVerification(string(), value);
        Assertions.assertThrows(InvalidRequestException.class, () -> userService.permitOtp(otpVerification));
    }

    @ParameterizedTest(name = "Invalid session id")
    @CsvSource({
            ",",
            "empty",
            "null"
    })
    public void shouldThrowInvalidRequestExceptionForInvalidOtpSessionId(
            @ConvertWith(AuthorizationTest.NullableConverter.class) String sessionId) {
        OtpVerification otpVerification = new OtpVerification(sessionId, string());
        Assertions.assertThrows(InvalidRequestException.class, () -> userService.permitOtp(otpVerification));
    }

    @Test
    public void shouldCreateUser() {
        var signUpRequest = signUpRequest().build();
        var userToken = keycloakToken().build();
        when(tokenService.tokenForAdmin()).thenReturn(Mono.just(new KeycloakToken()));
        when(identityServiceClient.createUser(any(), any())).thenReturn(Mono.empty());
        when(tokenService.tokenForUser(any(), any())).thenReturn(Mono.just(userToken));

        StepVerifier.create(userService.create(signUpRequest))
                .assertNext(response -> assertThat(response.getAccessToken()).isEqualTo(userToken.getAccessToken()))
                .verifyComplete();
    }

    @ParameterizedTest(name = "Invalid user name")
    @CsvSource({
            ",",
            "empty",
            "null"
    })
    public void shouldThrowInvalidRequestExceptionForInvalidUserId(
            @ConvertWith(AuthorizationTest.NullableConverter.class) String userId
    ) {
        var signUpRequest = signUpRequest().userName(userId).build();
        Assertions.assertThrows(InvalidRequestException.class, () -> userService.create(signUpRequest));
    }

    private static Stream<AbstractMap.SimpleEntry<String, String>> mobileNumberProvider() {
        return Stream.of(new AbstractMap.SimpleEntry<>("9788888", "9788888"),
                new AbstractMap.SimpleEntry<>("+91-9788888", "9788888"));
    }
}