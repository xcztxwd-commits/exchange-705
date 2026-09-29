package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;

/** Private attachment: never exposed through public /uploads routes. */
@Entity @Getter @Setter @Table(name = "support_attachment")
public class SupportAttachment {
    @Id private Long messageId;
    @Lob @Column(nullable = false, updatable = false, columnDefinition = "LONGBLOB")
    @com.fasterxml.jackson.annotation.JsonIgnore private byte[] content;
}
