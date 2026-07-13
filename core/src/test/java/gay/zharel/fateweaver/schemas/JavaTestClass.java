package gay.zharel.fateweaver.schemas;

public class JavaTestClass {
    public int normalField;
    public double anotherField;
    
    @FateIgnore
    public String ignoredField;
    
    public JavaTestClass(int normalField, double anotherField, String ignoredField) {
        this.normalField = normalField;
        this.anotherField = anotherField;
        this.ignoredField = ignoredField;
    }
}
