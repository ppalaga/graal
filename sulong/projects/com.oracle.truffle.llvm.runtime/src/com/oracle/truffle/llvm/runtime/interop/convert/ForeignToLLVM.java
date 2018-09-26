/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.runtime.interop.convert;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.llvm.runtime.interop.access.LLVMInteropType;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import com.oracle.truffle.llvm.runtime.types.ArrayType;
import com.oracle.truffle.llvm.runtime.types.PointerType;
import com.oracle.truffle.llvm.runtime.types.PrimitiveType;
import com.oracle.truffle.llvm.runtime.types.StructureType;
import com.oracle.truffle.llvm.runtime.types.Type;
import com.oracle.truffle.llvm.runtime.types.VectorType;
import com.oracle.truffle.llvm.runtime.types.VoidType;

public abstract class ForeignToLLVM extends LLVMNode {

    public abstract Object executeWithTarget(Object value);

    public abstract Object executeWithType(Object value, LLVMInteropType.Structured type);

    @Child protected Node isPointer = Message.IS_POINTER.createNode();
    @Child protected Node asPointer = Message.AS_POINTER.createNode();
    @Child protected Node isBoxed = Message.IS_BOXED.createNode();
    @Child protected Node unbox = Message.UNBOX.createNode();
    @Child protected Node toNativeNode = Message.TO_NATIVE.createNode();

    public Object fromForeign(TruffleObject value) {
        try {
            if (ForeignAccess.sendIsPointer(isPointer, value)) {
                return ForeignAccess.sendAsPointer(asPointer, value);
            } else if (ForeignAccess.sendIsBoxed(isBoxed, value)) {
                return ForeignAccess.sendUnbox(unbox, value);
            } else {
                return ForeignAccess.sendAsPointer(asPointer, (TruffleObject) ForeignAccess.sendToNative(toNativeNode, value));
            }
        } catch (InteropException e) {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedTypeException.raise(new Object[]{value});
        }
    }

    protected static boolean notLLVM(TruffleObject value) {
        return LLVMExpressionNode.notLLVM(value);
    }

    protected boolean checkIsPointer(TruffleObject object) {
        return ForeignAccess.sendIsPointer(isPointer, object);
    }

    protected char getSingleStringCharacter(String value) {
        if (value.length() == 1) {
            return value.charAt(0);
        } else {
            CompilerDirectives.transferToInterpreter();
            throw UnsupportedTypeException.raise(new Object[]{value});
        }
    }

    public enum ForeignToLLVMType {
        I1(1, false),
        I8(1, (byte) 0),
        I16(2, (short) 0),
        I32(4, 0),
        I64(8, 0L),
        FLOAT(4, 0f),
        DOUBLE(8, 0d),
        POINTER(8, 0L),
        VECTOR(-1, null),
        ARRAY(-1, null),
        STRUCT(-1, null),
        ANY(-1, null),
        VOID(-1, null);

        private final int size;
        private final Object defaultValue;

        ForeignToLLVMType(int size, Object defaultValue) {
            this.size = size;
            this.defaultValue = defaultValue;
        }

        public static ForeignToLLVMType getIntegerType(int bitWidth) {
            switch (bitWidth) {
                case 8:
                    return ForeignToLLVMType.I8;
                case 16:
                    return ForeignToLLVMType.I16;
                case 32:
                    return ForeignToLLVMType.I32;
                case 64:
                    return ForeignToLLVMType.I64;
                default:
                    CompilerDirectives.transferToInterpreter();
                    throw new IllegalStateException("There is no integer type with " + bitWidth + " bits defined");
            }
        }

        public int getSizeInBytes() {
            assert size > 0;
            return size;
        }

        public Object getDefaultValue() {
            assert defaultValue != null;
            return defaultValue;
        }

        public boolean isI1() {
            return this == ForeignToLLVMType.I1;
        }

        public boolean isI8() {
            return this == ForeignToLLVMType.I8;
        }

        public boolean isI16() {
            return this == ForeignToLLVMType.I16;
        }

        public boolean isI32() {
            return this == ForeignToLLVMType.I32;
        }

        public boolean isI64() {
            return this == ForeignToLLVMType.I64;
        }

        public boolean isFloat() {
            return this == ForeignToLLVMType.FLOAT;
        }

        public boolean isDouble() {
            return this == ForeignToLLVMType.DOUBLE;
        }

        public boolean isPointer() {
            return this == ForeignToLLVMType.POINTER;
        }
    }

    public static ForeignToLLVMType convert(Type type) {
        if (type instanceof PrimitiveType) {
            switch (((PrimitiveType) type).getPrimitiveKind()) {
                case I1:
                    return ForeignToLLVMType.I1;
                case I8:
                    return ForeignToLLVMType.I8;
                case I16:
                    return ForeignToLLVMType.I16;
                case I32:
                    return ForeignToLLVMType.I32;
                case I64:
                    return ForeignToLLVMType.I64;
                case FLOAT:
                    return ForeignToLLVMType.FLOAT;
                case DOUBLE:
                    return ForeignToLLVMType.DOUBLE;
                default:
                    throw UnsupportedTypeException.raise(new Object[]{type});
            }
        } else if (type instanceof PointerType) {
            return ForeignToLLVMType.POINTER;
        } else if (type instanceof VoidType) {
            return ForeignToLLVMType.VOID;
        } else if (type instanceof VectorType) {
            return ForeignToLLVMType.VECTOR;
        } else if (type instanceof ArrayType) {
            return ForeignToLLVMType.ARRAY;
        } else if (type instanceof StructureType) {
            return ForeignToLLVMType.STRUCT;
        } else {
            throw UnsupportedTypeException.raise(new Object[]{type});
        }
    }

    public static SlowPathForeignToLLVM createSlowPathNode() {
        return new SlowPathForeignToLLVM();
    }

    public static final class SlowPathForeignToLLVM extends ForeignToLLVM {
        @CompilationFinal private LLVMMemory memory;

        @TruffleBoundary
        public Object convert(Type type, Object value, LLVMInteropType.Value interopType) {
            return convert(ForeignToLLVM.convert(type), value, interopType);
        }

        @TruffleBoundary
        public Object convert(ForeignToLLVMType type, Object value, LLVMInteropType.Value interopType) {
            if (type == ForeignToLLVMType.ANY) {
                return ToAnyLLVM.slowPathPrimitiveConvert(value);
            } else if (type == ForeignToLLVMType.POINTER) {
                LLVMInteropType.Structured interopPointerType = interopType.getKind() == LLVMInteropType.ValueKind.POINTER ? interopType.getBaseType() : null;
                return ToPointer.slowPathPrimitiveConvert(value, interopPointerType);
            } else {
                if (memory == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    memory = getLLVMMemory();
                }
                switch (type) {
                    case DOUBLE:
                        return ToDouble.slowPathPrimitiveConvert(memory, this, value);
                    case FLOAT:
                        return ToFloat.slowPathPrimitiveConvert(memory, this, value);
                    case I1:
                        return ToI1.slowPathPrimitiveConvert(memory, this, value);
                    case I16:
                        return ToI16.slowPathPrimitiveConvert(memory, this, value);
                    case I32:
                        return ToI32.slowPathPrimitiveConvert(memory, this, value);
                    case I64:
                        return ToI64.slowPathPrimitiveConvert(memory, this, value);
                    case I8:
                        return ToI8.slowPathPrimitiveConvert(memory, this, value);
                    default:
                        throw new IllegalStateException(type.toString());
                }
            }
        }

        @Override
        public Object executeWithTarget(Object value) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("Use convert method.");
        }

        @Override
        public Object executeWithType(Object value, LLVMInteropType.Structured type) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("Use convert method.");
        }
    }
}
