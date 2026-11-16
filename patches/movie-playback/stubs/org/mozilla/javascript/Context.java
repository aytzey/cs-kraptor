package org.mozilla.javascript;
public class Context {
    public void setOptimizationLevel(int level) {}
    public void setInstructionObserverThreshold(int count) {}
    public void setClassShutter(ClassShutter shutter) {}
    public ScriptableObject initSafeStandardObjects() { return null; }
    public Object evaluateString(Scriptable scope, String source, String name, int line, Object domain) { return null; }
    public static void exit() {}
}
