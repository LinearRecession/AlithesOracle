package io.alithes.oracle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Strongly-typed root configuration bound from {@code oracle.*} properties.
 */
@ConfigurationProperties(prefix = "oracle")
public record OracleProperties(
        HttpClientProperties http,
        JevProperties jev,
        GdeltProperties gdelt,
        FetcherProperties fetcher,
        ScannerProperties scanner,
        WebhookProperties webhook,
        PolicyProperties policy,
        SecurityProperties security,
        NewsProperties news,
        AdmissionProperties admission,
        QueryProperties query,
        AssessorProperties assessor,
        SynthesisProperties synthesis,
        ScoringProperties scoring
) {
    @ConstructorBinding
    public OracleProperties {
        http = http != null ? http : HttpClientProperties.defaults();
        jev = jev != null ? jev : JevProperties.defaults();
        gdelt = gdelt != null ? gdelt : GdeltProperties.defaults();
        fetcher = fetcher != null ? fetcher : FetcherProperties.defaults();
        scanner = scanner != null ? scanner : ScannerProperties.defaults();
        webhook = webhook != null ? webhook : WebhookProperties.defaults();
        policy = policy != null ? policy : PolicyProperties.defaults();
        security = security != null ? security : SecurityProperties.defaults();
        news = news != null ? news : NewsProperties.defaults();
        admission = admission != null ? admission : AdmissionProperties.defaults();
        query = query != null ? query : QueryProperties.defaults();
        assessor = assessor != null ? assessor : AssessorProperties.defaults();
        synthesis = synthesis != null ? synthesis : SynthesisProperties.defaults();
        scoring = scoring != null ? scoring : ScoringProperties.defaults();
    }

    public OracleProperties(
            JevProperties jev,
            GdeltProperties gdelt,
            FetcherProperties fetcher,
            ScannerProperties scanner,
            WebhookProperties webhook,
            PolicyProperties policy,
            SecurityProperties security,
            NewsProperties news
    ) {
        this(null, jev, gdelt, fetcher, scanner, webhook, policy, security, news,
                null, null, null, null, null);
    }

    public static OracleProperties defaults() {
        return new OracleProperties(
                HttpClientProperties.defaults(),
                JevProperties.defaults(),
                GdeltProperties.defaults(),
                FetcherProperties.defaults(),
                ScannerProperties.defaults(),
                WebhookProperties.defaults(),
                PolicyProperties.defaults(),
                SecurityProperties.defaults(),
                NewsProperties.defaults(),
                AdmissionProperties.defaults(),
                QueryProperties.defaults(),
                AssessorProperties.defaults(),
                SynthesisProperties.defaults(),
                ScoringProperties.defaults()
        );
    }
                            }

                            
                                
                                    
                                
                            

                            
                                
                                    
                                
                            

                            
                                
                                    
                                            
                                
                            

                            
                                
                                    
                                            
                                
                            

                            
                                
                                    
                                            
                                
                            