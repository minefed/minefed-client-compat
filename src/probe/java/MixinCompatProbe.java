/* Copyright (c) 2026 Minefed. SPDX-License-Identifier: MIT */
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

public final class MixinCompatProbe {
    public static String TARGET="com.nik123123555.ptsdeco.crafting.WorkbenchContructingRecipe$Serializer";
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: MixinCompatProbe <original probe classes> <output directory> <mixin config> <PTS JAR>");
        Path original=Path.of(args[0]), output=Path.of(args[1]);
        String config=args[2];
        byte[] source;
        try(JarFile jar=new JarFile(args[3])){source=jar.getInputStream(jar.getJarEntry(TARGET.replace('.','/')+".class")).readAllBytes();}
        ProbeService.originalTarget=source;
        System.setProperty("mixin.service", ProbeService.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(config);
        ProbeService service=(ProbeService)MixinService.getService();
        IMixinTransformer transformer=service.factory().createTransformer();
        Path relative=Path.of(TARGET.replace('.', '/')+".class");
        byte[] transformed=transformer.transformClassBytes(TARGET,TARGET,source);
        if(Arrays.equals(source,transformed))throw new AssertionError("Mixin did not transform target");
        ClassNode result=new ClassNode();new ClassReader(transformed).accept(result,0);
        int reads=0,redirects=0;
        for(MethodNode method:result.methods)if(method.name.equals("fromNetwork"))for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call){
            if(call.owner.equals("net/minecraft/class_2540")&&call.name.equals("readBoolean"))reads++;
            if(call.owner.equals(TARGET.replace('.','/'))&&call.name.contains("minefed"))redirects++;
        }
        if(reads!=0||redirects!=1)throw new AssertionError("Unexpected transformed instructions: readBoolean="+reads+", redirect="+redirects);
        try(var files=Files.walk(original)){
            for(Path path:files.toList())if(Files.isRegularFile(path)){Path dest=output.resolve(original.relativize(path));Files.createDirectories(dest.getParent());Files.copy(path,dest,StandardCopyOption.REPLACE_EXISTING);}
        }
        Files.write(output.resolve("full-transformed-serializer.class"),transformed);
        Files.write(output.resolve(relative),isolateForExecution(transformed));
        Method runCase=PtsRecipeBoundaryProbe.class.getDeclaredMethod("runCase",ClassLoader.class,boolean.class,int.class);
        runCase.setAccessible(true);
        try(URLClassLoader isolated=new URLClassLoader(new URL[]{output.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            for(int ingredients:new int[]{0,1,3})runCase.invoke(null,isolated,true,ingredients);
        }
        System.out.println("PASS: real Sponge Mixin transformed the shipped compatibility redirect, including @Coerce Object; all recipe boundaries preserved.");
    }

    private static byte[] isolateForExecution(byte[] transformed){
        ClassWriter writer=new ClassWriter(0);
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9,writer){
            public void visit(int v,int access,String name,String signature,String parent,String[] interfaces){super.visit(v,access,name,null,parent,new String[0]);}
            public FieldVisitor visitField(int access,String name,String descriptor,String signature,Object value){return null;}
            public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                if(!Set.of("<init>","fromNetwork","toNetwork","lambda$fromNetwork$8","lambda$toNetwork$9").contains(name)&&!name.contains("minefed"))return null;
                return super.visitMethod(access,name,descriptor,null,exceptions);
            }
        },0);
        return writer.toByteArray();
    }

    public static final class ProbeService extends MixinServiceAbstract implements IClassProvider,IClassBytecodeProvider {
        static byte[] originalTarget;
        static Path fixtureClasses;
        /** Optional internal-name to bytes map, e.g. every class of an official JAR. */
        static Map<String,byte[]> officialClasses=Map.of();
        public String getName(){return "Minefed isolated Mixin probe";}
        public boolean isValid(){return true;}
        public MixinEnvironment.Phase getInitialPhase(){return MixinEnvironment.Phase.DEFAULT;}
        public IClassProvider getClassProvider(){return this;}
        public IClassBytecodeProvider getBytecodeProvider(){return this;}
        public ITransformerProvider getTransformerProvider(){return null;}
        public IClassTracker getClassTracker(){return null;}
        public IMixinAuditTrail getAuditTrail(){return null;}
        public Collection<String> getPlatformAgents(){return List.of();}
        public IContainerHandle getPrimaryContainer(){return new ContainerHandleVirtual("minefed-probe");}
        public InputStream getResourceAsStream(String name){return getClass().getClassLoader().getResourceAsStream(name);}
        public URL[] getClassPath(){return new URL[0];}
        public Class<?> findClass(String name)throws ClassNotFoundException{return Class.forName(name,false,getClass().getClassLoader());}
        public Class<?> findClass(String name,boolean init)throws ClassNotFoundException{return Class.forName(name,init,getClass().getClassLoader());}
        public Class<?> findAgentClass(String name,boolean init)throws ClassNotFoundException{return findClass(name,init);}
        public ClassNode getClassNode(String name)throws ClassNotFoundException,IOException{return getClassNode(name,true,0);}
        public ClassNode getClassNode(String name,boolean transform)throws ClassNotFoundException,IOException{return getClassNode(name,transform,0);}
        public ClassNode getClassNode(String name,boolean transform,int flags)throws ClassNotFoundException,IOException{
            if(name.replace('/','.').equals(TARGET)){ClassNode node=new ClassNode();new ClassReader(originalTarget).accept(node,flags);return node;}
            byte[] official=officialClasses.get(name.replace('.','/'));
            if(official!=null){ClassNode node=new ClassNode();new ClassReader(official).accept(node,flags);return node;}
            String resource=name.replace('.','/')+".class";
            InputStream available=getResourceAsStream(resource);
            if(available==null && fixtureClasses!=null && Files.isRegularFile(fixtureClasses.resolve(resource)))
                available=Files.newInputStream(fixtureClasses.resolve(resource));
            try(InputStream stream=available){
                if(stream==null)throw new ClassNotFoundException(name);
                ClassNode node=new ClassNode();new ClassReader(stream).accept(node,flags);return node;
            }
        }
        public IMixinTransformerFactory factory(){return getInternal(IMixinTransformerFactory.class);}
    }

    public static final class Properties implements IGlobalPropertyService {
        private record Key(String name) implements IPropertyKey{}
        private final Map<IPropertyKey,Object> values=new HashMap<>();
        public IPropertyKey resolveKey(String name){return new Key(name);}
        @SuppressWarnings("unchecked") public <T>T getProperty(IPropertyKey key){return (T)values.get(key);}
        public void setProperty(IPropertyKey key,Object value){values.put(key,value);}
        @SuppressWarnings("unchecked") public <T>T getProperty(IPropertyKey key,T fallback){return (T)values.getOrDefault(key,fallback);}
        public String getPropertyString(IPropertyKey key,String fallback){return String.valueOf(values.getOrDefault(key,fallback));}
    }
}
