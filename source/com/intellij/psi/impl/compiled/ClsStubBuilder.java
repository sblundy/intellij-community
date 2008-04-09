/*
 * @author max
 */
package com.intellij.psi.impl.compiled;

import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.PsiNameHelper;
import com.intellij.psi.PsiReferenceList;
import com.intellij.psi.impl.cache.ModifierFlags;
import com.intellij.psi.impl.cache.impl.repositoryCache.TypeInfo;
import com.intellij.psi.impl.java.stubs.PsiClassStub;
import com.intellij.psi.impl.java.stubs.PsiFieldStub;
import com.intellij.psi.impl.java.stubs.PsiModifierListStub;
import com.intellij.psi.impl.java.stubs.impl.*;
import com.intellij.psi.stubs.PsiFileStub;
import com.intellij.psi.stubs.StubElement;
import com.intellij.util.cls.ClsFormatException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.EmptyVisitor;

import java.io.IOException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings({"HardCodedStringLiteral"})
public class ClsStubBuilder {
  private ClsStubBuilder() {
  }

  @Nullable
  public static PsiFileStub build(final VirtualFile vFile, byte[] bytes) throws ClsFormatException {
    final PsiJavaFileStubImpl file = new PsiJavaFileStubImpl("dont.know.yet");
    try {
      final PsiClassStub result = buildClass(vFile, bytes, file);
      if (result == null) return null;

      file.setPackageName(getPackageName(result));
    }
    catch (Exception e) {
      throw new ClsFormatException();
    }
    return file;
  }

  private static PsiClassStub buildClass(final VirtualFile vFile, final byte[] bytes, final StubElement parent) {
    ClassReader reader = new ClassReader(bytes);

    final MyClassVisitor classVisitor = new MyClassVisitor(vFile, parent);
    reader.accept(classVisitor, ClassReader.SKIP_CODE);
    return classVisitor.getResult();
  }

  private static String getPackageName(final PsiClassStub result) {
    final String fqn = result.getQualifiedName();
    final String shortName = result.getName();
    if (fqn == null || Comparing.equal(shortName, fqn)) {
      return "";
    }

    return fqn.substring(0, fqn.lastIndexOf('.'));
  }

  private static class MyClassVisitor implements ClassVisitor {
    private final StubElement myParent;
    private final VirtualFile myVFile;
    private PsiModifierListStub myModlist;
    private PsiClassStub myResult;
    private static final String[] EMPTY_STRINGS = new String[0];
    @NonNls private static final String SYNTHETIC_CLINIT_METHOD = "<clinit>";
    @NonNls private static final String SYNTHETIC_INIT_METHOD = "<init>";


    private MyClassVisitor(final VirtualFile vFile, final StubElement parent) {
      myVFile = vFile;
      myParent = parent;
    }

    public PsiClassStub getResult() {
      return myResult;
    }

    public void visit(final int version,
                      final int access,
                      final String name,
                      final String signature,
                      final String superName,
                      final String[] interfaces) {
      String fqn = Type.getObjectType(name).getClassName();

      final boolean isInner = fqn.contains("$");
      if (isInner) {
        fqn = fqn.replace('$', '.');
      }

      final String shortName = PsiNameHelper.getShortClassName(fqn);

      boolean isDeprecated = (access & Opcodes.ACC_DEPRECATED) != 0;
      boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
      boolean isEnum = (access & Opcodes.ACC_ENUM) != 0;
      boolean isAnnotationType = (access & Opcodes.ACC_ANNOTATION) != 0;

      final byte flags = PsiClassStubImpl.packFlags(isDeprecated, isInterface, isEnum, false, false, isAnnotationType, false);

      myResult = new PsiClassStubImpl(myParent, fqn, shortName, null, flags);

      ((PsiClassStubImpl)myResult).setLanguageLevel(convertFromVersion(version));
      myModlist = new PsiModifierListStubImpl(myResult, packModlistFlags(access));

      CharacterIterator signatureIterator = signature != null ? new StringCharacterIterator(signature) : null;
      if (signatureIterator != null) {
        try {
          SignatureParsing.parseTypeParametersDeclaration(signatureIterator, myResult);
        }
        catch (ClsFormatException e) {
          signatureIterator = null;
        }
      }

      String convertedSuper;
      List<String> convertedInterfaces = new ArrayList<String>();
      if (signatureIterator == null) {
        convertedSuper = parseClassDescription(superName, interfaces, convertedInterfaces);
      }
      else {
        try {
          convertedSuper = parseClassSignature(signatureIterator, convertedInterfaces);
        }
        catch (ClsFormatException e) {
          convertedSuper = parseClassDescription(superName, interfaces, convertedInterfaces);
        }
      }

      String[] interfacesArray = convertedInterfaces.toArray(new String[convertedInterfaces.size()]);
      if (isInterface) {
        new PsiClassReferenceListStubImpl(myResult, interfacesArray, PsiReferenceList.Role.EXTENDS_LIST);
        new PsiClassReferenceListStubImpl(myResult, EMPTY_STRINGS, PsiReferenceList.Role.IMPLEMENTS_LIST);
      }
      else {
        if (convertedSuper != null && !"java.lang.Object".equals(convertedSuper)) {
          new PsiClassReferenceListStubImpl(myResult, new String[] {convertedSuper}, PsiReferenceList.Role.EXTENDS_LIST);
        }
        else {
          new PsiClassReferenceListStubImpl(myResult, EMPTY_STRINGS, PsiReferenceList.Role.EXTENDS_LIST);
        }
        new PsiClassReferenceListStubImpl(myResult, interfacesArray, PsiReferenceList.Role.IMPLEMENTS_LIST);
      }
    }

    @Nullable
    private static String parseClassDescription(final String superName, final String[] interfaces, final List<String> convertedInterfaces) {
      final String convertedSuper;
      convertedSuper = superName != null ? Type.getObjectType(superName).getClassName() : null;
      for (String anInterface : interfaces) {
        convertedInterfaces.add(Type.getObjectType(anInterface).getClassName());
      }
      return convertedSuper;
    }

    @Nullable
    private static String parseClassSignature(final CharacterIterator signatureIterator, final List<String> convertedInterfaces)
        throws ClsFormatException {
      final String convertedSuper;
      convertedSuper = SignatureParsing.parseToplevelClassRefSignature(signatureIterator);
      while (signatureIterator.current() != CharacterIterator.DONE) {
        final String ifs = SignatureParsing.parseToplevelClassRefSignature(signatureIterator);
        if (ifs == null) throw new ClsFormatException();

        convertedInterfaces.add(ifs);
      }
      return convertedSuper;
    }

    private static LanguageLevel convertFromVersion(final int version) {
      if (version == Opcodes.V1_1 || version == Opcodes.V1_2 || version == Opcodes.V1_3) {
        return LanguageLevel.JDK_1_3;
      }

      if (version == Opcodes.V1_4) {
        return LanguageLevel.JDK_1_4;
      }

      if (version == Opcodes.V1_5 || version == Opcodes.V1_6) {
        return LanguageLevel.JDK_1_5;
      }

      return LanguageLevel.HIGHEST;
    }

    private static int packModlistFlags(final int access) {
      int flags = 0;

      if ((access & Opcodes.ACC_PRIVATE) != 0) {
        flags |= ModifierFlags.PRIVATE_MASK;
      }
      else if ((access & Opcodes.ACC_PROTECTED) != 0) {
        flags |= ModifierFlags.PROTECTED_MASK;
      }
      else if ((access & Opcodes.ACC_PUBLIC) != 0) {
        flags |= ModifierFlags.PUBLIC_MASK;
      }
      else {
        flags |= ModifierFlags.PACKAGE_LOCAL_MASK;
      }

      if ((access & Opcodes.ACC_ABSTRACT) != 0) {
        flags |= ModifierFlags.ABSTRACT_MASK;
      }
      if ((access & Opcodes.ACC_FINAL) != 0) {
        flags |= ModifierFlags.FINAL_MASK;
      }
      if ((access & Opcodes.ACC_NATIVE) != 0) {
        flags |= ModifierFlags.NATIVE_MASK;
      }
      if ((access & Opcodes.ACC_STATIC) != 0) {
        flags |= ModifierFlags.STATIC_MASK;
      }
      if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
        flags |= ModifierFlags.SYNCHRONIZED_MASK;
      }
      if ((access & Opcodes.ACC_TRANSIENT) != 0) {
        flags |= ModifierFlags.TRANSIENT_MASK;
      }
      if ((access & Opcodes.ACC_VOLATILE) != 0) {
        flags |= ModifierFlags.VOLATILE_MASK;
      }
      if ((access & Opcodes.ACC_STRICT) != 0) {
        flags |= ModifierFlags.STRICTFP_MASK;
      }

      return flags;
    }

    public void visitSource(final String source, final String debug) {
      ((PsiClassStubImpl)myResult).setSourceFileName(source);
    }

    public void visitOuterClass(final String owner, final String name, final String desc) {
    }

    public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
      return new AnnotationTextCollector(desc, new AnnotationResultCallback() {
        public void callback(final String text) {
          new PsiAnnotationStubImpl(myModlist, text);
        }
      });
    }

    public void visitAttribute(final Attribute attr) {
    }

    public void visitInnerClass(final String name, final String outerName, final String innerName, final int access) {
      if (innerName != null && outerName != null && Type.getObjectType(outerName).getClassName().equals(myResult.getQualifiedName())) {
        final String basename = myVFile.getNameWithoutExtension();
        final VirtualFile dir = myVFile.getParent();
        assert dir != null;

        final VirtualFile innerFile = dir.findChild(basename + "$" + innerName + ".class");
        if (innerFile != null) {
          try {
            buildClass(innerFile, innerFile.contentsToByteArray(), myResult);
          }
          catch (IOException e) {
            // No inner class file found, ignore.
          }
        }
      }
    }

    public FieldVisitor visitField(final int access, final String name, final String desc, final String signature, final Object value) {
      final byte flags = PsiFieldStubImpl.packFlags((access & Opcodes.ACC_ENUM) != 0, (access & Opcodes.ACC_DEPRECATED) != 0);
      PsiFieldStub stub = new PsiFieldStubImpl(myResult, name, fieldType(desc, signature), constToString(value), flags);
      final PsiModifierListStub modlist = new PsiModifierListStubImpl(stub, packModlistFlags(access));
      return new AnnotationCollectingVisitor(stub, modlist);
    }

    private static TypeInfo fieldType(String desc, String signature) {
      if (signature != null) {
        try {
          return TypeInfo.fromString(SignatureParsing.parseTypeString(new StringCharacterIterator(signature, 0)));
        }
        catch (ClsFormatException e) {
          return fieldTypeViaDescription(desc);
        }
      }
      else {
        return fieldTypeViaDescription(desc);
      }
    }

    private static TypeInfo fieldTypeViaDescription(final String desc) {
      Type type = Type.getType(desc);
      final int dim = type.getSort() == Type.ARRAY ? type.getDimensions() : 0;
      if (dim > 0) {
        type = type.getElementType();
      }
      final TypeInfo info = new TypeInfo();
      info.arrayCount = (byte)dim;
      info.text = type.getClassName();
      info.isEllipsis = false;
      return info;
    }


    @Nullable
    public MethodVisitor visitMethod(final int access,
                                     final String name,
                                     final String desc,
                                     final String signature,
                                     final String[] exceptions) {
      if ((access & Opcodes.ACC_SYNTHETIC) != 0) return null;
      if ((access & Opcodes.ACC_BRIDGE) != 0) return null;
      if (SYNTHETIC_CLINIT_METHOD.equals(name)) return null;

      boolean isDeprecated = (access & Opcodes.ACC_DEPRECATED) != 0;
      boolean isConstructor = SYNTHETIC_INIT_METHOD.equals(name);
      boolean isVarargs = (access & Opcodes.ACC_VARARGS) != 0;
      boolean isAnnotationMethod = myResult.isAnnotationType();

      final byte flags = PsiMethodStubImpl.packFlags(isConstructor, isAnnotationMethod, isVarargs, isDeprecated);

      String canonicalMethodName = isConstructor ? myResult.getName() : name;
      PsiMethodStubImpl stub = new PsiMethodStubImpl(myResult, canonicalMethodName, null, flags, null);
      PsiModifierListStub modlist = new PsiModifierListStubImpl(stub, packModlistFlags(access));

      String returnType;
      List<String> args = new ArrayList<String>();
      if (signature == null) {
        returnType = parseMethodViaDescription(desc, stub, args);
      }
      else {
        try {
          returnType = parseMethodViaGenericSignature(signature, stub, args);
        }
        catch (ClsFormatException e) {
          returnType = parseMethodViaDescription(desc, stub, args);
        }
      }
      stub.setReturnType(TypeInfo.fromString(returnType));

      final PsiParameterListStubImpl parameterList = new PsiParameterListStubImpl(stub);
      final int paramCount = args.size();
      for (int i = 0; i < paramCount; i++) {
        String arg = args.get(i);
        boolean isEllipsisParam = isVarargs && i == (paramCount - 1);
        final TypeInfo typeInfo = TypeInfo.fromString(arg);
        if (isEllipsisParam) {
          typeInfo.isEllipsis = true;
          typeInfo.arrayCount--;
        }

        PsiParameterStubImpl parameterStub = new PsiParameterStubImpl(parameterList, "p" + (i + 1), typeInfo, isEllipsisParam);
        new PsiModifierListStubImpl(parameterStub, 0);
      }

      if (exceptions != null) {
        String[] converted = new String[exceptions.length];
        for (int i = 0; i < converted.length; i++) {
          converted[i] = Type.getObjectType(exceptions[i]).getClassName();
        }
        new PsiClassReferenceListStubImpl(stub, converted, PsiReferenceList.Role.THROWS_LIST);
      }
      else {
        new PsiClassReferenceListStubImpl(stub, EMPTY_STRINGS, PsiReferenceList.Role.THROWS_LIST);
      }

      return new AnnotationCollectingVisitor(stub, modlist);
    }

    private static String parseMethodViaDescription(final String desc, final PsiMethodStubImpl stub, final List<String> args) {
      final String returnType;
      returnType = Type.getReturnType(desc).getClassName();
      final Type[] argTypes = Type.getArgumentTypes(desc);
      for (Type argType : argTypes) {
        args.add(argType.getClassName());
      }
      new PsiTypeParameterListStubImpl(stub);
      return returnType;
    }

    private static String parseMethodViaGenericSignature(final String signature, final PsiMethodStubImpl stub, final List<String> args)
        throws ClsFormatException {
      final String returnType;
      StringCharacterIterator iterator = new StringCharacterIterator(signature);
      SignatureParsing.parseTypeParametersDeclaration(iterator, stub);

      if (iterator.current() != '(') {
        throw new ClsFormatException();
      }
      iterator.next();

      while (iterator.current() != ')' && iterator.current() != CharacterIterator.DONE) {
        args.add(SignatureParsing.parseTypeString(iterator));
      }

      if (iterator.current() != ')') {
        throw new ClsFormatException();
      }
      iterator.next();

      returnType = SignatureParsing.parseTypeString(iterator);
      return returnType;
    }

    public void visitEnd() {
    }
  }

  private static class AnnotationTextCollector implements AnnotationVisitor {
    private final StringBuilder myBuilder = new StringBuilder();
    private final AnnotationResultCallback myCallback;
    private boolean hasParams = false;
    private final String myDesc;

    public AnnotationTextCollector(@Nullable String desc, AnnotationResultCallback callback) {
      myCallback = callback;

      myDesc = desc;
      if (desc != null) {
        myBuilder.append('@').append(Type.getType(desc).getClassName());
      }
    }

    public void visit(final String name, final Object value) {
      valuePairPrefix(name);
      myBuilder.append(constToString(value));
    }

    public void visitEnum(final String name, final String desc, final String value) {
      valuePairPrefix(name);
      myBuilder.append(value);
    }

    private void valuePairPrefix(final String name) {
      if (!hasParams) {
        hasParams = true;
        if (myDesc != null) {
          myBuilder.append('(');
        }
      }
      else {
        myBuilder.append(',');
      }

      if (name != null && !"value".equals(name)) {
        myBuilder.append(name).append('=');
      }
    }

    public AnnotationVisitor visitAnnotation(final String name, final String desc) {
      valuePairPrefix(name);
      return new AnnotationTextCollector(desc, new AnnotationResultCallback() {
        public void callback(final String text) {
          myBuilder.append(text);
        }
      });
    }

    public AnnotationVisitor visitArray(final String name) {
      valuePairPrefix(name);
      myBuilder.append("{");
      return new AnnotationTextCollector(null, new AnnotationResultCallback() {
        public void callback(final String text) {
          myBuilder.append(text).append('}');
        }
      });
    }

    public void visitEnd() {
      if (hasParams && myDesc != null) {
        myBuilder.append(')');
      }
      myCallback.callback(myBuilder.toString());
    }
  }

  private static class AnnotationCollectingVisitor extends EmptyVisitor {
    private final StubElement myOwner;
    private final PsiModifierListStub myModList;

    private AnnotationCollectingVisitor(final StubElement owner, final PsiModifierListStub modList) {
      myOwner = owner;
      myModList = modList;
    }

    public AnnotationVisitor visitAnnotationDefault() {
      return new AnnotationTextCollector(null, new AnnotationResultCallback() {
        public void callback(final String text) {
          ((PsiMethodStubImpl)myOwner).setDefaultValueText(text);
        }
      });
    }

    public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
      return new AnnotationTextCollector(desc, new AnnotationResultCallback() {
        public void callback(final String text) {
          new PsiAnnotationStubImpl(myModList, text);
        }
      });
    }

    public AnnotationVisitor visitParameterAnnotation(final int parameter, final String desc, final boolean visible) {
      return new AnnotationTextCollector(desc, new AnnotationResultCallback() {
        public void callback(final String text) {
          new PsiAnnotationStubImpl(((PsiMethodStubImpl)myOwner).findParameter(parameter), text);
        }
      });
    }
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  @Nullable
  private static String constToString(final Object value) {
    if (value == null) return null;

    if (value instanceof String) return "\"" + StringUtil.escapeStringCharacters((String)value) + "\"";
    if (value instanceof Integer) return value.toString();
    if (value instanceof Long) return value.toString() + "L";

    if (value instanceof Double) {
      final double d = ((Double)value).doubleValue();
      if (Double.isInfinite(d)) {
        return d > 0 ? "Double.POSITIVE_INFINITY" : "Double.NEGATIVE_INFINITY";
      }
      else if (Double.isNaN(d)) {
        return "Double.NaN";
      }
      return Double.toString(d);
    }

    if (value instanceof Float) {
      final float v = ((Float)value).floatValue();

      if (Float.isInfinite(v)) {
        return v > 0 ? "Float.POSITIVE_INFINITY" : "Float.NEGATIVE_INFINITY";
      }
      else if (Float.isNaN(v)) {
        return "Float.NaN";
      }
      else {
        return Float.toString(v) + "f";
      }
    }

    return null;
  }

  private interface AnnotationResultCallback {
    void callback(String text);
  }
}