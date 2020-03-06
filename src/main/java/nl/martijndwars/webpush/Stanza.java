package nl.martijndwars.webpush;

public class Stanza {
    public String msgType;
    public String msgFrom;
    public String msgBody;

    public Stanza() { }

    public Stanza(String msgType, String msgFrom, String msgBody) {
        this.msgFrom = msgFrom;
        this.msgFrom = msgFrom;
        this.msgBody = msgBody;
    }
}
