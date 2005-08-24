/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ATHROW;
import org.apache.bcel.generic.ConstantPoolGen;
import org.apache.bcel.generic.Instruction;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionTargeter;
import org.apache.bcel.generic.InvokeInstruction;
import org.apache.bcel.generic.MethodGen;
import org.apache.bcel.generic.ReturnInstruction;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.Detector;
import edu.umd.cs.findbugs.FindBugsAnalysisFeatures;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.BasicBlock;
import edu.umd.cs.findbugs.ba.CFGBuilderException;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.ba.DataflowAnalysisException;
import edu.umd.cs.findbugs.ba.DataflowValueChooser;
import edu.umd.cs.findbugs.ba.Edge;
import edu.umd.cs.findbugs.ba.Hierarchy;
import edu.umd.cs.findbugs.ba.JavaClassAndMethod;
import edu.umd.cs.findbugs.ba.Location;
import edu.umd.cs.findbugs.ba.MissingClassException;
import edu.umd.cs.findbugs.ba.NullnessAnnotation;
import edu.umd.cs.findbugs.ba.NullnessAnnotationDatabase;
import edu.umd.cs.findbugs.ba.SignatureConverter;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.ba.interproc.PropertyDatabase;
import edu.umd.cs.findbugs.ba.npe.IsNullValue;
import edu.umd.cs.findbugs.ba.npe.IsNullValueDataflow;
import edu.umd.cs.findbugs.ba.npe.IsNullValueFrame;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonCollector;
import edu.umd.cs.findbugs.ba.npe.NullDerefAndRedundantComparisonFinder;
import edu.umd.cs.findbugs.ba.npe.ParameterNullnessProperty;
import edu.umd.cs.findbugs.ba.npe.ParameterNullnessPropertyDatabase;
import edu.umd.cs.findbugs.ba.npe.RedundantBranch;
import edu.umd.cs.findbugs.ba.type.TypeDataflow;
import edu.umd.cs.findbugs.ba.type.TypeFrame;
import edu.umd.cs.findbugs.ba.vna.ValueNumber;
import edu.umd.cs.findbugs.ba.vna.ValueNumberDataflow;
import edu.umd.cs.findbugs.ba.vna.ValueNumberFrame;
import edu.umd.cs.findbugs.props.GeneralWarningProperty;
import edu.umd.cs.findbugs.props.WarningPropertySet;
import edu.umd.cs.findbugs.props.WarningPropertyUtil;

/**
 * A Detector to find instructions where a NullPointerException
 * might be raised.  We also look for useless reference comparisons
 * involving null and non-null values.
 *
 * @author David Hovemeyer
 * @author William Pugh
 * @see edu.umd.cs.findbugs.ba.npe.IsNullValueAnalysis
 */
public class FindNullDeref
		implements Detector, NullDerefAndRedundantComparisonCollector {

	private static final boolean DEBUG = Boolean.getBoolean("fnd.debug");
	private static final boolean DEBUG_NULLARG = Boolean.getBoolean("fnd.debug.nullarg");
	private static final boolean DEBUG_NULLRETURN = Boolean.getBoolean("fnd.debug.nullreturn");
	private static final boolean REPORT_SAFE_METHOD_TARGETS = true;

	private static final String METHOD = System.getProperty("fnd.method");
	
	// Fields
	private BugReporter bugReporter;
	
	// Cached database stuff
	private ParameterNullnessPropertyDatabase unconditionalDerefParamDatabase;
	private boolean checkUnconditionalDeref;
	private boolean checkedDatabases = false;

	
	// Transient state
	private ClassContext classContext;
	private Method method;
	private IsNullValueDataflow invDataflow;
	private BitSet previouslyDeadBlocks;
	private NullnessAnnotation methodAnnotation;

	public FindNullDeref(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
	}

	public void visitClassContext(ClassContext classContext) {
		this.classContext = classContext;
		
		try {
			JavaClass jclass = classContext.getJavaClass();
			Method[] methodList = jclass.getMethods();
			for (Method method : methodList) {
				if (method.isAbstract() || method.isNative() || method.getCode() == null)
					continue;

				if (METHOD != null && !method.getName().equals(METHOD))
					continue;
				if (DEBUG) System.out.println("Checking for NP in " + method.getName());
				analyzeMethod(classContext, method);
			}
		} catch (MissingClassException e) {
			bugReporter.reportMissingClass(e.getClassNotFoundException());
		} catch (DataflowAnalysisException e) {
			bugReporter.logError("FindNullDeref caught dae exception", e);
		} catch (CFGBuilderException e) {
			bugReporter.logError("FindNullDeref caught cfgb exception", e);
		}
	}

	private void analyzeMethod(ClassContext classContext, Method method)
	        throws CFGBuilderException, DataflowAnalysisException {
		if (classContext.getMethodGen(method) == null)
			return;
		if (!checkedDatabases) {
			checkDatabases();
			checkedDatabases = true;
		}
		
		this.method = method;
		this.methodAnnotation = getMethodNullnessAnnotation();


		if (DEBUG || DEBUG_NULLARG)
			System.out.println(SignatureConverter.convertMethodSignature(classContext.getMethodGen(method)));

		this.previouslyDeadBlocks = findPreviouslyDeadBlocks();
		
		// Get the IsNullValueDataflow for the method from the ClassContext
		invDataflow = classContext.getIsNullValueDataflow(method);
		
		// Create a NullDerefAndRedundantComparisonFinder object to do the actual
		// work.  It will call back to report null derefs and redundant null comparisons
		// through the NullDerefAndRedundantComparisonCollector interface we implement.
		NullDerefAndRedundantComparisonFinder worker = new NullDerefAndRedundantComparisonFinder(
				classContext,
				method,
				invDataflow,
				this);
		worker.execute();


		checkCallSitesAndReturnInstructions();
		
	}

	/**
	 * Find set of blocks which were known to be dead before doing the
	 * null pointer analysis.
	 * 
	 * @return set of previously dead blocks, indexed by block id
	 * @throws CFGBuilderException 
	 * @throws DataflowAnalysisException 
	 */
	private BitSet findPreviouslyDeadBlocks() throws DataflowAnalysisException, CFGBuilderException {
		BitSet deadBlocks = new BitSet();
		ValueNumberDataflow vnaDataflow = classContext.getValueNumberDataflow(method);
		for (Iterator<BasicBlock> i = vnaDataflow.getCFG().blockIterator(); i.hasNext();) {
			BasicBlock block = i.next();
			ValueNumberFrame vnaFrame = vnaDataflow.getStartFact(block);
			if (vnaFrame.isTop()) {
				deadBlocks.set(block.getId());
			}
		}
		
		return deadBlocks;
	}

	/**
	 * Check whether or not the various interprocedural databases we can
	 * use exist and are nonempty.
	 */
	private void checkDatabases() {
		AnalysisContext analysisContext = AnalysisContext.currentAnalysisContext();
		unconditionalDerefParamDatabase = analysisContext.getUnconditionalDerefParamDatabase();		
	}

	private<
		DatabaseType extends PropertyDatabase<?,?>> boolean isDatabaseNonEmpty(DatabaseType database) {
		return database != null && !database.isEmpty();
	}

	/**
	 * See if the currently-visited method declares a @NonNull annotation,
	 * or overrides a method which declares a @NonNull annotation.
	 */
	private NullnessAnnotation getMethodNullnessAnnotation() {
		
		if (method.getSignature().indexOf(")L") >= 0 || method.getSignature().indexOf(")[") >= 0 ) {
			if (DEBUG_NULLRETURN) {
				System.out.println("Checking return annotation for " +
						SignatureConverter.convertMethodSignature(classContext.getJavaClass(), method));
			}
			
			XMethod m = XFactory.createXMethod(classContext.getJavaClass(), method);
			return AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase()
			.getResolvedAnnotation(m, false);
		}
		return NullnessAnnotation.UNKNOWN_NULLNESS;
	}

	private void checkCallSitesAndReturnInstructions()
			throws CFGBuilderException, DataflowAnalysisException {
		ConstantPoolGen cpg = classContext.getConstantPoolGen();
		TypeDataflow typeDataflow = classContext.getTypeDataflow(method);
		
		for (Iterator<Location> i = classContext.getCFG(method).locationIterator(); i.hasNext();) {
			Location location = i.next();
			Instruction ins = location.getHandle().getInstruction();
			try {
				if (ins instanceof InvokeInstruction) {
					examineCallSite(location, cpg, typeDataflow);
				} else if (methodAnnotation == NullnessAnnotation.NONNULL && ins.getOpcode() == Constants.ARETURN) {
					examineReturnInstruction(location);
				}
			} catch (ClassNotFoundException e) {
				bugReporter.reportMissingClass(e);
			}
		}
	}

	private void examineCallSite(
			Location location,
			ConstantPoolGen cpg,
			TypeDataflow typeDataflow)
			throws DataflowAnalysisException, CFGBuilderException, ClassNotFoundException {
		
		InvokeInstruction invokeInstruction = (InvokeInstruction)
			location.getHandle().getInstruction();
		
		if (DEBUG_NULLARG) {
			System.out.println("Examining call site: " + location.getHandle());
		}

		String methodName = invokeInstruction.getName(cpg);
		String signature = invokeInstruction.getSignature(cpg);
		
		// Don't check equals() calls.
		// If an equals() call unconditionally dereferences the parameter,
		// it is the fault of the method, not the caller.
		if (methodName.equals("equals") && signature.equals("(Ljava/lang/Object;)Z"))
			return;
		
		int returnTypeStart = signature.indexOf(')');
		if (returnTypeStart < 0)
			return;
		String paramList = signature.substring(0, returnTypeStart + 1);
		
		if (paramList.equals("()") ||
				(paramList.indexOf("L") < 0 && paramList.indexOf('[') < 0))
			// Method takes no arguments, or takes no reference arguments
			return;

		// See if any null arguments are passed
		IsNullValueFrame frame =
			classContext.getIsNullValueDataflow(method).getFactAtLocation(location);
		if (!frame.isValid())
			return;
		BitSet nullArgSet = frame.getArgumentSet(invokeInstruction, cpg, new DataflowValueChooser<IsNullValue>() {
			public boolean choose(IsNullValue value) {
				// Only choose non-exception values.
				// Values null on an exception path might be due to
				// infeasible control flow.
				return value.mightBeNull() && !value.isException();
			}
		});
		BitSet definitelyNullArgSet = frame.getArgumentSet(invokeInstruction, cpg, new DataflowValueChooser<IsNullValue>() {
			public boolean choose(IsNullValue value) {
				return value.isDefinitelyNull();
			}
		});
		if (nullArgSet.isEmpty())
			return;
		if (DEBUG_NULLARG) {
			System.out.println("Null arguments passed: " + nullArgSet);
		}
		
		if (unconditionalDerefParamDatabase != null) {
			checkUnconditionallyDereferencedParam(location, cpg, typeDataflow, invokeInstruction, nullArgSet, definitelyNullArgSet);
		}
		
		
			if (DEBUG_NULLARG) {
				System.out.println("Checking nonnull params");
			}
			checkNonNullParam(location, cpg, typeDataflow, invokeInstruction, nullArgSet, definitelyNullArgSet);
		
	}
	
	private void examineReturnInstruction(Location location) throws DataflowAnalysisException, CFGBuilderException {
		if (DEBUG_NULLRETURN) {
			System.out.println("Checking null return at " + location);
		}
		
		IsNullValueDataflow invDataflow = classContext.getIsNullValueDataflow(method);
		IsNullValueFrame frame = invDataflow.getFactAtLocation(location);
		if (!frame.isValid())
			return;
		IsNullValue tos = frame.getTopValue();
		if (tos.mightBeNull()) {
			MethodGen methodGen = classContext.getMethodGen(method);
			String sourceFile = classContext.getJavaClass().getSourceFileName();
						
			BugInstance warning = new BugInstance("NP_NONNULL_RETURN_VIOLATION", tos.isDefinitelyNull() ?
					HIGH_PRIORITY : NORMAL_PRIORITY)
				.addClassAndMethod(methodGen, sourceFile)
				.addSourceLine(classContext, methodGen, sourceFile, location.getHandle());
			
			bugReporter.reportBug(warning);
		}
	}

	private void checkUnconditionallyDereferencedParam(
			Location location,
			ConstantPoolGen cpg,
			TypeDataflow typeDataflow,
			InvokeInstruction invokeInstruction,
			BitSet nullArgSet, BitSet definitelyNullArgSet) throws DataflowAnalysisException, ClassNotFoundException {
		
		// See what methods might be called here
		TypeFrame typeFrame = typeDataflow.getFactAtLocation(location);
		Set<JavaClassAndMethod> targetMethodSet = Hierarchy.resolveMethodCallTargets(invokeInstruction, typeFrame, cpg);
		if (DEBUG_NULLARG) {
			System.out.println("Possibly called methods: " + targetMethodSet);
		}
		
		// See if any call targets unconditionally dereference one of the null arguments
		BitSet unconditionallyDereferencedNullArgSet = new BitSet();
		List<JavaClassAndMethod> dangerousCallTargetList = new LinkedList<JavaClassAndMethod>();
		List<JavaClassAndMethod> veryDangerousCallTargetList = new LinkedList<JavaClassAndMethod>();
		for (JavaClassAndMethod targetMethod : targetMethodSet) {
			if (DEBUG_NULLARG) {
				System.out.println("For target method " + targetMethod);
			}
			
			ParameterNullnessProperty property = unconditionalDerefParamDatabase.getProperty(targetMethod.toXMethod());
			if (property == null)
				continue;
			if (DEBUG_NULLARG) {
				System.out.println("\tUnconditionally dereferenced params: " + property);
			}
			
			BitSet targetUnconditionallyDereferencedNullArgSet =
				property.getViolatedParamSet(nullArgSet);
			
			if (targetUnconditionallyDereferencedNullArgSet.isEmpty())
				continue;
			
			dangerousCallTargetList.add(targetMethod);
			
			unconditionallyDereferencedNullArgSet.or(targetUnconditionallyDereferencedNullArgSet);
			
			if (!property.getViolatedParamSet(definitelyNullArgSet).isEmpty())
				veryDangerousCallTargetList.add(targetMethod);
		}
		
		if (dangerousCallTargetList.isEmpty())
			return;
		
		WarningPropertySet propertySet = new WarningPropertySet();

		// See if there are any safe targets
		Set<JavaClassAndMethod> safeCallTargetSet = new HashSet<JavaClassAndMethod>();
		safeCallTargetSet.addAll(targetMethodSet);
		safeCallTargetSet.removeAll(dangerousCallTargetList);
		if (safeCallTargetSet.isEmpty()) {
			propertySet.addProperty(NullArgumentWarningProperty.ALL_DANGEROUS_TARGETS);
			if (dangerousCallTargetList.size() == 1) {
				propertySet.addProperty(NullArgumentWarningProperty.MONOMORPHIC_CALL_SITE);
			}
		}
		
		// Call to private method?  In theory there should be only one possible target.
		boolean privateCall =
			   safeCallTargetSet.isEmpty()
			&& dangerousCallTargetList.size() == 1
			&& dangerousCallTargetList.get(0).getMethod().isPrivate();
		
		MethodGen methodGen = classContext.getMethodGen(method);
		String sourceFile = classContext.getJavaClass().getSourceFileName();
		
		String bugType;
		int priority;
		if (privateCall
				|| invokeInstruction.getOpcode() == Constants.INVOKESTATIC
				|| invokeInstruction.getOpcode() == Constants.INVOKESPECIAL) {
			bugType = "NP_NULL_PARAM_DEREF_NONVIRTUAL";
			priority = HIGH_PRIORITY;
		} else if (safeCallTargetSet.isEmpty()) {
			bugType = "NP_NULL_PARAM_DEREF_ALL_TARGETS_DANGEROUS";
			priority = NORMAL_PRIORITY;
		} else {
			bugType = "NP_NULL_PARAM_DEREF";
			priority = LOW_PRIORITY;
		}
		
		if (dangerousCallTargetList.size() > veryDangerousCallTargetList.size())
			priority++;
		else
			propertySet.addProperty(NullArgumentWarningProperty.ACTUAL_PARAMETER_GUARANTEED_NULL);
		
		BugInstance warning = new BugInstance(bugType, priority)
				.addClassAndMethod(methodGen, sourceFile)
				.addSourceLine(classContext, methodGen, sourceFile, location.getHandle())
				.addMethod(XFactory.createXMethod(invokeInstruction, cpg)).describe("METHOD_CALLED");
		
		// Check which params might be null
		addParamAnnotations(definitelyNullArgSet, unconditionallyDereferencedNullArgSet, propertySet, warning);

		// Add annotations for dangerous method call targets
		for (JavaClassAndMethod dangerousCallTarget : veryDangerousCallTargetList) {
			warning.addMethod(dangerousCallTarget).describe("METHOD_DANGEROUS_TARGET_ACTUAL_GUARANTEED_NULL");
		}
		dangerousCallTargetList.removeAll(veryDangerousCallTargetList);
//		 Add annotations for dangerous method call targets
		for (JavaClassAndMethod dangerousCallTarget : dangerousCallTargetList) {
			warning.addMethod(dangerousCallTarget).describe("METHOD_DANGEROUS_TARGET");
		}

		// Add safe method call targets.
		// This is useful to see which other call targets the analysis
		// considered.
		for (JavaClassAndMethod safeMethod : safeCallTargetSet) {
			warning.addMethod(safeMethod).describe("METHOD_SAFE_TARGET");
		}
		
		decorateWarning(location, propertySet, warning);
		bugReporter.reportBug(warning);
	}

	private void decorateWarning(Location location, WarningPropertySet propertySet, BugInstance warning) {
		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertyUtil.addPropertiesForLocation(propertySet, classContext, method, location);
		}
		propertySet.decorateBugInstance(warning);
	}

	private void addParamAnnotations(
			BitSet definitelyNullArgSet,
			BitSet violatedParamSet,
			WarningPropertySet propertySet,
			BugInstance warning) {
		for (int i = 0; i < 32; ++i) {
			if (violatedParamSet.get(i)) {
				boolean definitelyNull = definitelyNullArgSet.get(i);
				
				if (definitelyNull) {
					propertySet.addProperty(NullArgumentWarningProperty.ARG_DEFINITELY_NULL);
				}

				// Note: we report params as being indexed starting from 1, not 0
				warning.addInt(i + 1).describe(
						definitelyNull ? "INT_NULL_ARG" : "INT_MAYBE_NULL_ARG");
			}
		}
	}

	/**
	 * We have a method invocation in which a possibly or definitely null
	 * parameter is passed. Check it against the library of nonnull annotations.
	 * 
	 * @param location
	 * @param cpg
	 * @param typeDataflow
	 * @param invokeInstruction
	 * @param nullArgSet
	 * @param definitelyNullArgSet
	 * @throws ClassNotFoundException
	 */
	private void checkNonNullParam(
			Location location, 
			ConstantPoolGen cpg,
			TypeDataflow typeDataflow,
			InvokeInstruction invokeInstruction,
			BitSet nullArgSet,
			BitSet definitelyNullArgSet) throws ClassNotFoundException {
		
		XMethod m = XFactory.createXMethod(invokeInstruction, cpg);
		if (m.getClassName().startsWith("java")) {
			// at the moment, none of these are annotation
			return;
		}
		NullnessAnnotationDatabase db 
		= AnalysisContext.currentAnalysisContext().getNullnessAnnotationDatabase();
		for(int i=nullArgSet.nextSetBit(0); i>=0; i=nullArgSet.nextSetBit(i+1)) 
			if (db.parameterMustBeNonNull(m, i)) {
				boolean definitelyNull = definitelyNullArgSet.get(i);
				
				MethodGen methodGen = classContext.getMethodGen(method);
				String sourceFile = classContext.getJavaClass().getSourceFileName();
				
				BugInstance warning = new BugInstance("NP_NONNULL_PARAM_VIOLATION", 
						definitelyNull ? HIGH_PRIORITY : NORMAL_PRIORITY)
						.addClassAndMethod(methodGen, sourceFile)
						.addSourceLine(classContext, methodGen, sourceFile, location.getHandle())
						.addMethod(m).describe("METHOD_CALLED");
				warning.addInt(i).describe("INT_NONNULL_PARAM");
				
				bugReporter.reportBug(warning);
			}
		
	}

	public void report() {
	}

	public void foundNullDeref(Location location, ValueNumber valueNumber, IsNullValue refValue) {
		WarningPropertySet propertySet = new WarningPropertySet();
		
		boolean onExceptionPath = refValue.isException();
		if (onExceptionPath) {
			propertySet.addProperty(GeneralWarningProperty.ON_EXCEPTION_PATH);
		}
		
		if (refValue.isDefinitelyNull()) {
			String type = onExceptionPath ? "NP_ALWAYS_NULL_EXCEPTION" : "NP_ALWAYS_NULL";
			int priority = onExceptionPath ? NORMAL_PRIORITY : HIGH_PRIORITY;
			reportNullDeref(propertySet, classContext, method, location, type, priority);
		} else if (refValue.isNullOnSomePath()) {
			String type = onExceptionPath ? "NP_NULL_ON_SOME_PATH_EXCEPTION" : "NP_NULL_ON_SOME_PATH";
			int priority = onExceptionPath ? LOW_PRIORITY : NORMAL_PRIORITY;
			if (refValue.isReturnValue())
				type = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE";
			if (DEBUG) System.out.println("Reporting null on some path: value=" + refValue);
			reportNullDeref(propertySet, classContext, method, location, type, priority);
		}
	}

	private void reportNullDeref(
			WarningPropertySet propertySet,
			ClassContext classContext,
			Method method,
			Location location,
			String type,
			int priority) {
		MethodGen methodGen = classContext.getMethodGen(method);
		String sourceFile = classContext.getJavaClass().getSourceFileName();

		BugInstance bugInstance = new BugInstance(this, type, priority)
		        .addClassAndMethod(methodGen, sourceFile)
		        .addSourceLine(classContext, methodGen, sourceFile, location.getHandle());

		if (DEBUG)
			bugInstance.addInt(location.getHandle().getPosition()).describe("INT_BYTECODE_OFFSET");

		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertyUtil.addPropertiesForLocation(propertySet, classContext, method, location);
			propertySet.decorateBugInstance(bugInstance);
		}
		
		bugReporter.reportBug(bugInstance);
	}

	public static boolean isThrower(BasicBlock target) {
		InstructionHandle ins = target.getFirstInstruction();
		int maxCount = 7;
		while (ins != null) {
			if (maxCount-- <= 0) break;
			Instruction i = ins.getInstruction();
			if (i instanceof ATHROW) {
				return true;
			}
			if (i instanceof InstructionTargeter 
					|| i instanceof ReturnInstruction) return false;
			ins = ins.getNext();
		}
		return false;
	}
	public void foundRedundantNullCheck(Location location, RedundantBranch redundantBranch) {
		String sourceFile = classContext.getJavaClass().getSourceFileName();
		MethodGen methodGen = classContext.getMethodGen(method);
		
		boolean isChecked = redundantBranch.firstValue.isChecked();
		boolean wouldHaveBeenAKaboom = redundantBranch.firstValue.wouldHaveBeenAKaboom();
		Location locationOfKaBoom = redundantBranch.firstValue.getLocationOfKaBoom();
		 
		boolean createdDeadCode = false;
		boolean infeasibleEdgeSimplyThrowsException = false;
		Edge infeasibleEdge = redundantBranch.infeasibleEdge;
		if (infeasibleEdge != null) {
			if (DEBUG) System.out.println("Check if " + redundantBranch + " creates dead code");
			BasicBlock target = infeasibleEdge.getTarget();

			if (DEBUG) System.out.println("Target block is  " + (target.isExceptionThrower() ? " exception thrower" : " not exception thrower"));
			// If the block is empty, it probably doesn't matter that it was killed.
			// FIXME: really, we should crawl the immediately reachable blocks
			// starting at the target block to see if any of them are dead and nonempty.
			boolean empty =  !target.isExceptionThrower() &&
				(target.isEmpty() || isGoto(target.getFirstInstruction().getInstruction()));
			if (!empty) {
				try {
				if (classContext.getCFG(method).getNumIncomingEdges(target) > 1) {
					if (DEBUG) System.out.println("Target of infeasible edge has multiple incoming edges");
					empty = true;
				}}
				catch (CFGBuilderException e) {
					assert true; // ignore it
				}
			}
			if (DEBUG) System.out.println("Target block is  " + (empty ? "empty" : "not empty"));

			if (!empty) {
				if (isThrower(target)) infeasibleEdgeSimplyThrowsException = true;
		
			}
			if (!empty && !previouslyDeadBlocks.get(target.getId())) {
				if (DEBUG) System.out.println("target was alive previously");
				// Block was not dead before the null pointer analysis.
				// See if it is dead now by inspecting the null value frame.
				// If it's TOP, then the block became dead.
				IsNullValueFrame invFrame = invDataflow.getStartFact(target);
				createdDeadCode = invFrame.isTop();
				if (DEBUG) System.out.println("target is now " + (createdDeadCode ? "dead" : "alive"));

			}
		}
		
		int priority;
		boolean valueIsNull = true;
		String warning;
		if (redundantBranch.secondValue == null) {
			if (redundantBranch.firstValue.isDefinitelyNull() ) {
				warning = "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE";
				priority = NORMAL_PRIORITY;
			}
			else {
				warning = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE";
				valueIsNull = false; 
				priority = isChecked ? NORMAL_PRIORITY : LOW_PRIORITY;
			}

		} else {
			boolean bothNull =  redundantBranch.firstValue.isDefinitelyNull() && redundantBranch.secondValue.isDefinitelyNull();		
			if (redundantBranch.secondValue.isChecked()) isChecked = true;
			if (redundantBranch.secondValue.wouldHaveBeenAKaboom()) wouldHaveBeenAKaboom = true;
			if (bothNull) {
				warning = "RCN_REDUNDANT_COMPARISON_TWO_NULL_VALUES";
				priority = NORMAL_PRIORITY;
			}
			else {
				warning = "RCN_REDUNDANT_COMPARISON_OF_NULL_AND_NONNULL_VALUE";
				priority = isChecked ? NORMAL_PRIORITY : LOW_PRIORITY;
			}

		}
		
		if (wouldHaveBeenAKaboom) {
			priority = HIGH_PRIORITY;
			warning = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE";
			if (locationOfKaBoom == null) throw new NullPointerException("location of KaBoom is null");
		}
		
		if (DEBUG) System.out.println(createdDeadCode + " " + infeasibleEdgeSimplyThrowsException + " " + valueIsNull + " " + priority);
		if (createdDeadCode && !infeasibleEdgeSimplyThrowsException) {
			priority += 0;
		} else if (createdDeadCode && infeasibleEdgeSimplyThrowsException) {
			// throw clause
			if (valueIsNull) 
				priority += 0;
			else
				priority += 1;
		} else {
			// didn't create any dead code
			priority += 1;
		}


		if (DEBUG) {
			System.out.println("RCN" + priority + " " 
					+ redundantBranch.firstValue + " =? "
					+ redundantBranch.secondValue 
					+ " : " + warning 
			);

			if (isChecked) System.out.println("isChecked");
			if (wouldHaveBeenAKaboom) System.out.println("wouldHaveBeenAKaboom");
			if (createdDeadCode) System.out.println("createdDeadCode");
		}
		
		BugInstance bugInstance =
			new BugInstance(this, warning, priority)
				.addClassAndMethod(methodGen, sourceFile);
		if (wouldHaveBeenAKaboom) 
			bugInstance.addSourceLine(classContext, methodGen, sourceFile, locationOfKaBoom.getHandle());
		bugInstance.addSourceLine(classContext, methodGen, sourceFile, location.getHandle()).describe("SOURCE_REDUNDANT_NULL_CHECK");
		
		if (FindBugsAnalysisFeatures.isRelaxedMode()) {
			WarningPropertySet propertySet = new WarningPropertySet();
			WarningPropertyUtil.addPropertiesForLocation(propertySet, classContext, method, location);
			if (isChecked) 
				propertySet.addProperty(NullDerefProperty.CHECKED_VALUE);
			if (wouldHaveBeenAKaboom) 
				propertySet.addProperty(NullDerefProperty.WOULD_HAVE_BEEN_A_KABOOM);
			if (createdDeadCode)
				propertySet.addProperty(NullDerefProperty.CREATED_DEAD_CODE);
			
			propertySet.decorateBugInstance(bugInstance);
			
			priority = propertySet.computePriority(NORMAL_PRIORITY);
			bugInstance.setPriority(priority);
		}

		bugReporter.reportBug(bugInstance);
	}

	/**
	 * Determine whether or not given instruction is a goto.
	 * 
	 * @param instruction the instruction
	 * @return true if the instruction is a goto, false otherwise
	 */
	private boolean isGoto(Instruction instruction) {
		return instruction.getOpcode() == Constants.GOTO
			|| instruction.getOpcode() == Constants.GOTO_W;
	}

}

// vim:ts=4
