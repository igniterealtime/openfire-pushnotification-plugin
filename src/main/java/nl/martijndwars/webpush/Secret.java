package nl.martijndwars.webpush;

public class Secret {
    public String privateKey;
    public String publicKey;
    public Subscription subscription;

    public Secret() { }

    public Secret(String privateKey, String publicKey, Subscription subscription) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.subscription = subscription;
    }

}
