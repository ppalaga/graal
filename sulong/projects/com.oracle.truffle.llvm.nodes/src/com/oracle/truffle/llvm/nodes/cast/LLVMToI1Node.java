/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.nodes.cast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.llvm.runtime.LLVMBoxedPrimitive;
import com.oracle.truffle.llvm.runtime.LLVMIVarBit;
import com.oracle.truffle.llvm.runtime.floating.LLVM80BitFloat;
import com.oracle.truffle.llvm.runtime.interop.convert.ForeignToLLVM;
import com.oracle.truffle.llvm.runtime.interop.convert.ForeignToLLVM.ForeignToLLVMType;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;
import com.oracle.truffle.llvm.runtime.vector.LLVMI1Vector;

@NodeChild(value = "fromNode", type = LLVMExpressionNode.class)
public abstract class LLVMToI1Node extends LLVMExpressionNode {

    @Specialization
    protected boolean doManaged(LLVMManagedPointer from,
                    @Cached("createForeignToLLVM()") ForeignToLLVM toLLVM,
                    @Cached("createIsNull()") Node isNull,
                    @Cached("createIsBoxed()") Node isBoxed,
                    @Cached("createUnbox()") Node unbox) {
        TruffleObject base = from.getObject();
        if (ForeignAccess.sendIsNull(isNull, base)) {
            return (from.getOffset() & 1) != 0;
        } else if (ForeignAccess.sendIsBoxed(isBoxed, base)) {
            try {
                boolean ptr = (boolean) toLLVM.executeWithTarget(ForeignAccess.sendUnbox(unbox, base));
                return ptr ^ ((from.getOffset() & 1) != 0);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }
        CompilerDirectives.transferToInterpreter();
        throw new IllegalStateException("Not convertable");
    }

    @Specialization
    protected boolean doLLVMBoxedPrimitive(LLVMBoxedPrimitive from,
                    @Cached("createForeignToLLVM()") ForeignToLLVM toLLVM) {
        return (boolean) toLLVM.executeWithTarget(from.getValue());
    }

    @Specialization
    protected boolean doNativePointer(LLVMNativePointer from) {
        return (from.asNative() & 1L) != 0;
    }

    protected ForeignToLLVM createForeignToLLVM() {
        return getNodeFactory().createForeignToLLVM(ForeignToLLVMType.I1);
    }

    public abstract static class LLVMSignedCastToI1Node extends LLVMToI1Node {
        @Specialization
        protected boolean doI1(boolean from) {
            return from;
        }

        @Specialization
        protected boolean doI1(byte from) {
            return (from & 1) != 0;
        }

        @Specialization
        protected boolean doI1(short from) {
            return (from & 1) != 0;
        }

        @Specialization
        protected boolean doI1(int from) {
            return (from & 1) != 0;
        }

        @Specialization
        protected boolean doI1(long from) {
            return (from & 1) != 0;
        }

        @Specialization
        protected boolean doI1(LLVMIVarBit from) {
            return doI1(from.getByteValue());
        }

        @Specialization
        protected boolean doI1(float from) {
            return from != 0;
        }

        @Specialization
        protected boolean doI1(double from) {
            return from != 0;
        }

        @Specialization
        protected boolean doLLVM80BitFloat(LLVM80BitFloat from) {
            return from.getLongValue() != 0;
        }
    }

    public abstract static class LLVMBitcastToI1Node extends LLVMToI1Node {

        @Specialization
        protected boolean doI1(boolean from) {
            return from;
        }

        @Specialization
        protected boolean doI1Vector(LLVMI1Vector from) {
            assert from.getLength() == 1 : "invalid vector size";
            return from.getValue(0);
        }
    }
}
