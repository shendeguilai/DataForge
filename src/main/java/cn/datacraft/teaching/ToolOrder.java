package cn.datacraft.teaching;

import jakarta.persistence.*;

@Entity
@Table(name="site_tool_order")
public class ToolOrder {
    @Id public Long id=1L;
    @Column(nullable=false,columnDefinition="TEXT") public String payload;
    @Version public long version;
}
