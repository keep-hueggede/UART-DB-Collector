import jakarta.persistence.*;

@Entity
@Table(name = "UnitTest")
public class Hibernate_UnitTest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id")
    private int id;

    @Column(name = "Key")
    private String key;

    @Column(name = "Val")
    private Integer val;

     public Hibernate_UnitTest(String _key, Integer _val){
         this.key = _key;
         this.val = _val;
     }


    public int getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public Integer getVal() {
        return val;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setVal(Integer val) {
        this.val = val;
    }
}
