package com.fcz.oauth.config.security.token;

import java.util.Date;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.common.DefaultExpiringOAuth2RefreshToken;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.DefaultOAuth2RefreshToken;
import org.springframework.security.oauth2.common.ExpiringOAuth2RefreshToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2RefreshToken;
import org.springframework.security.oauth2.common.exceptions.InvalidGrantException;
import org.springframework.security.oauth2.common.exceptions.InvalidScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.ClientRegistrationException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.token.*;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * TokenService类 实现参考DefaultTokenServices类
 * 1. 获取token机制改变，每次获取token都去新建一个token，不去查询是否存在去共享token（可以多终端登录该账号）
 * 2. loadAuthentication方法 token过期后不删除token，后续刷新token需要refresh_token参数，通过该token查询到refresh_token
 * @author Mr.F
 * @since 2019/7/10 15:32
 * @see org.springframework.security.oauth2.provider.token.DefaultTokenServices
 **/
@Component
public class SystemTokenServices  implements AuthorizationServerTokenServices, ResourceServerTokenServices, ConsumerTokenServices, InitializingBean{

    private int refreshTokenValiditySeconds = 2592000;
    private int accessTokenValiditySeconds = 43200;
    private boolean supportRefreshToken = false;
    private boolean reuseRefreshToken = true;

    @Autowired
    private TokenStore jdbcTokenStore;
    
    private ClientDetailsService clientDetailsService;
    private TokenEnhancer accessTokenEnhancer;
    private AuthenticationManager authenticationManager;

    public SystemTokenServices() {
    }


    public void afterPropertiesSet() {
        Assert.notNull(this.jdbcTokenStore, "tokenStore must be set");
    }

    /**
     * description  创建新的token 每个用户登录都新创建一个token 不共享token
     * @author Mr.F
     * @since 2019/7/11
     **/
    @Transactional
    public OAuth2AccessToken createAccessToken(OAuth2Authentication authentication) throws AuthenticationException {

        OAuth2RefreshToken refreshToken = null;
        refreshToken = this.createRefreshToken(authentication);

        OAuth2AccessToken accessToken = this.createAccessToken(authentication, refreshToken);
        this.jdbcTokenStore.storeAccessToken(accessToken, authentication);
        refreshToken = accessToken.getRefreshToken();
        if (refreshToken != null) {
            this.jdbcTokenStore.storeRefreshToken(refreshToken, authentication);
        }

        return accessToken;
    }

    @Transactional(
            noRollbackFor = {InvalidTokenException.class, InvalidGrantException.class}
    )
    public OAuth2AccessToken refreshAccessToken(String refreshTokenValue, TokenRequest tokenRequest) throws AuthenticationException {
        if (!this.supportRefreshToken) {
            throw new InvalidGrantException("Invalid refresh token: " + refreshTokenValue);
        } else {
            OAuth2RefreshToken refreshToken = this.jdbcTokenStore.readRefreshToken(refreshTokenValue);
            if (refreshToken == null) {
                throw new InvalidGrantException("Invalid refresh token: " + refreshTokenValue);
            } else {
                OAuth2Authentication authentication = this.jdbcTokenStore.readAuthenticationForRefreshToken(refreshToken);
                if (this.authenticationManager != null && !authentication.isClientOnly()) {
                    Authentication user = new PreAuthenticatedAuthenticationToken(authentication.getUserAuthentication(), "", authentication.getAuthorities());
                    user = this.authenticationManager.authenticate(user);
                    Object details = authentication.getDetails();
                    authentication = new OAuth2Authentication(authentication.getOAuth2Request(), user);
                    authentication.setDetails(details);
                }

                String clientId = authentication.getOAuth2Request().getClientId();
                if (clientId != null && clientId.equals(tokenRequest.getClientId())) {
                    this.jdbcTokenStore.removeAccessTokenUsingRefreshToken(refreshToken);
                    if (this.isExpired(refreshToken)) {
                        this.jdbcTokenStore.removeRefreshToken(refreshToken);
                        throw new InvalidTokenException("Invalid refresh token (expired): " + refreshToken);
                    } else {
                        authentication = this.createRefreshedAuthentication(authentication, tokenRequest);
                        if (!this.reuseRefreshToken) {
                            this.jdbcTokenStore.removeRefreshToken(refreshToken);
                            refreshToken = this.createRefreshToken(authentication);
                        }

                        OAuth2AccessToken accessToken = this.createAccessToken(authentication, refreshToken);
                        this.jdbcTokenStore.storeAccessToken(accessToken, authentication);
                        if (!this.reuseRefreshToken) {
                            this.jdbcTokenStore.storeRefreshToken(accessToken.getRefreshToken(), authentication);
                        }

                        return accessToken;
                    }
                } else {
                    throw new InvalidGrantException("Wrong client for this refresh token: " + refreshTokenValue);
                }
            }
        }
    }

    public OAuth2AccessToken getAccessToken(OAuth2Authentication authentication) {
        return this.jdbcTokenStore.getAccessToken(authentication);
    }

    private OAuth2Authentication createRefreshedAuthentication(OAuth2Authentication authentication, TokenRequest request) {
        Set<String> scope = request.getScope();
        OAuth2Request clientAuth = authentication.getOAuth2Request().refresh(request);
        if (scope != null && !scope.isEmpty()) {
            Set<String> originalScope = clientAuth.getScope();
            if (originalScope == null || !originalScope.containsAll(scope)) {
                throw new InvalidScopeException("Unable to narrow the scope of the client authentication to " + scope + ".", originalScope);
            }

            clientAuth = clientAuth.narrowScope(scope);
        }

        OAuth2Authentication narrowed = new OAuth2Authentication(clientAuth, authentication.getUserAuthentication());
        return narrowed;
    }

    protected boolean isExpired(OAuth2RefreshToken refreshToken) {
        if (!(refreshToken instanceof ExpiringOAuth2RefreshToken)) {
            return false;
        } else {
            ExpiringOAuth2RefreshToken expiringToken = (ExpiringOAuth2RefreshToken)refreshToken;
            return expiringToken.getExpiration() == null || System.currentTimeMillis() > expiringToken.getExpiration().getTime();
        }
    }

    public OAuth2AccessToken readAccessToken(String accessToken) {
        return this.jdbcTokenStore.readAccessToken(accessToken);
    }

    public OAuth2Authentication loadAuthentication(String accessTokenValue) throws AuthenticationException, InvalidTokenException {
        OAuth2AccessToken accessToken = this.jdbcTokenStore.readAccessToken(accessTokenValue);
        if (accessToken == null) {
            // token 在数据库中不存在
            // 错误原因: 1. 使用了错误的token  2. 多终端使用一个账号 导致token冲突
            // 第二种情况需要重新获取token
            throw new InvalidTokenException("Invalid access token: " + accessTokenValue);
        } else if (accessToken.isExpired()) {
            // 先不删除access token
//            this.jdbcTokenStore.removeAccessToken(accessToken);
            throw new InvalidTokenException("Access token expired: " + accessTokenValue);
        } else {
            OAuth2Authentication result = this.jdbcTokenStore.readAuthentication(accessToken);
            if (result == null) {
                throw new InvalidTokenException("Invalid access token: " + accessTokenValue);
            } else {
                if (this.clientDetailsService != null) {
                    String clientId = result.getOAuth2Request().getClientId();

                    try {
                        this.clientDetailsService.loadClientByClientId(clientId);
                    } catch (ClientRegistrationException var6) {
                        throw new InvalidTokenException("Client not valid: " + clientId, var6);
                    }
                }

                return result;
            }
        }
    }

    public String getClientId(String tokenValue) {
        OAuth2Authentication authentication = this.jdbcTokenStore.readAuthentication(tokenValue);
        if (authentication == null) {
            throw new InvalidTokenException("Invalid access token: " + tokenValue);
        } else {
            OAuth2Request clientAuth = authentication.getOAuth2Request();
            if (clientAuth == null) {
                throw new InvalidTokenException("Invalid access token (no client id): " + tokenValue);
            } else {
                return clientAuth.getClientId();
            }
        }
    }

    public boolean revokeToken(String tokenValue) {
        OAuth2AccessToken accessToken = this.jdbcTokenStore.readAccessToken(tokenValue);
        if (accessToken == null) {
            return false;
        } else {
            if (accessToken.getRefreshToken() != null) {
                this.jdbcTokenStore.removeRefreshToken(accessToken.getRefreshToken());
            }

            this.jdbcTokenStore.removeAccessToken(accessToken);
            return true;
        }
    }

    private OAuth2RefreshToken createRefreshToken(OAuth2Authentication authentication) {
        if (!this.isSupportRefreshToken(authentication.getOAuth2Request())) {
            return null;
        } else {
            int validitySeconds = this.getRefreshTokenValiditySeconds(authentication.getOAuth2Request());
            String value = UUID.randomUUID().toString();
            return (OAuth2RefreshToken)(validitySeconds > 0 ? new DefaultExpiringOAuth2RefreshToken(value, new Date(System.currentTimeMillis() + (long)validitySeconds * 1000L)) : new DefaultOAuth2RefreshToken(value));
        }
    }

    private OAuth2AccessToken createAccessToken(OAuth2Authentication authentication, OAuth2RefreshToken refreshToken) {
        DefaultOAuth2AccessToken token = new DefaultOAuth2AccessToken(UUID.randomUUID().toString());
        int validitySeconds = this.getAccessTokenValiditySeconds(authentication.getOAuth2Request());
        if (validitySeconds > 0) {
            token.setExpiration(new Date(System.currentTimeMillis() + (long)validitySeconds * 1000L));
        }

        token.setRefreshToken(refreshToken);
        token.setScope(authentication.getOAuth2Request().getScope());
        return (OAuth2AccessToken)(this.accessTokenEnhancer != null ? this.accessTokenEnhancer.enhance(token, authentication) : token);
    }

    protected int getAccessTokenValiditySeconds(OAuth2Request clientAuth) {
        if (this.clientDetailsService != null) {
            ClientDetails client = this.clientDetailsService.loadClientByClientId(clientAuth.getClientId());
            Integer validity = client.getAccessTokenValiditySeconds();
            if (validity != null) {
                return validity;
            }
        }

        return this.accessTokenValiditySeconds;
    }

    protected int getRefreshTokenValiditySeconds(OAuth2Request clientAuth) {
        if (this.clientDetailsService != null) {
            ClientDetails client = this.clientDetailsService.loadClientByClientId(clientAuth.getClientId());
            Integer validity = client.getRefreshTokenValiditySeconds();
            if (validity != null) {
                return validity;
            }
        }

        return this.refreshTokenValiditySeconds;
    }

    protected boolean isSupportRefreshToken(OAuth2Request clientAuth) {
        if (this.clientDetailsService != null) {
            ClientDetails client = this.clientDetailsService.loadClientByClientId(clientAuth.getClientId());
            return client.getAuthorizedGrantTypes().contains("refresh_token");
        } else {
            return this.supportRefreshToken;
        }
    }

    public void setTokenEnhancer(TokenEnhancer accessTokenEnhancer) {
        this.accessTokenEnhancer = accessTokenEnhancer;
    }

    public void setRefreshTokenValiditySeconds(int refreshTokenValiditySeconds) {
        this.refreshTokenValiditySeconds = refreshTokenValiditySeconds;
    }

    public void setAccessTokenValiditySeconds(int accessTokenValiditySeconds) {
        this.accessTokenValiditySeconds = accessTokenValiditySeconds;
    }

    public void setSupportRefreshToken(boolean supportRefreshToken) {
        this.supportRefreshToken = supportRefreshToken;
    }

    public void setReuseRefreshToken(boolean reuseRefreshToken) {
        this.reuseRefreshToken = reuseRefreshToken;
    }

    public void setTokenStore(TokenStore tokenStore) {
        this.jdbcTokenStore = tokenStore;
    }

    public void setAuthenticationManager(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    public void setClientDetailsService(ClientDetailsService clientDetailsService) {
        this.clientDetailsService = clientDetailsService;
    }
}
