package fr.aeria.backoffice.db;

/** A management rule (RGx) refused the operation; the message is meant for the user. */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
