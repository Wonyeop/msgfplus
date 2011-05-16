package msgf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import msutil.AminoAcid;
import msutil.AminoAcidSet;
import msutil.Annotation;
import msutil.Composition;
import msutil.Enzyme;
import msutil.Peptide;
import msutil.Modification.Location;

public class FlexDeNovoGraph extends DeNovoGraph<NominalMass> {
	private ScoredSpectrum<NominalMass> scoredSpec;
	private Enzyme enzyme;
	private boolean direction;	// true: forward (e.g. Lys-C), false: reverse (e.g. Trypsin)
	private AminoAcidSet aaSet;
	private boolean useProtNTerm;
	private boolean useProtCTerm;
	
	private HashMap<NominalMass, ArrayList<DeNovoGraph.Edge<NominalMass>>> edgeMap;
	private HashMap<NominalMass, Integer> nodeScore;
	
	public FlexDeNovoGraph(
			AminoAcidSet aaSet,
			float parentMass, 
			Tolerance pmTolerance, 
			Enzyme enzyme, 
			ScoredSpectrum<NominalMass> scoredSpec
		) 
	{
		this.enzyme = enzyme;
		this.direction = scoredSpec.getMainIonDirection();
		this.scoredSpec = scoredSpec;
		this.aaSet = aaSet;
		this.useProtNTerm = false;
		this.useProtCTerm = false;

		super.source = new NominalMass(0);
		
		float peptideMass = parentMass - (float)Composition.H2O;
		super.pmNode = new NominalMass(NominalMass.toNominalMass(peptideMass));

		edgeMap = new HashMap<NominalMass, ArrayList<DeNovoGraph.Edge<NominalMass>>>();
		edgeMap.put(source, null);
		setForwardEdgesFromSource();
		setForwardEdgesFromIntermediateNodes();
		super.intermediateNodes = new ArrayList<NominalMass>(edgeMap.keySet());
		Collections.sort(super.intermediateNodes);
		this.setBackwardEdgesFromSink();
		
		super.sinkNodes = new ArrayList<NominalMass>();
		sinkNodes.add(pmNode);
		
		computeNodeScores();
		computeEdgeScores();
		computeCleavageScores();
	}

	public FlexDeNovoGraph useProtNTerm()
	{
		this.useProtNTerm = true;
		return this;
	}

	public FlexDeNovoGraph useProtCTerm()
	{
		this.useProtCTerm = true;
		return this;
	}
	
	@Override
	public NominalMass getComplementNode(NominalMass node) {
		return new NominalMass(pmNode.getNominalMass()-node.getNominalMass());
	}
	
	@Override
	public ArrayList<DeNovoGraph.Edge<NominalMass>> getEdges(NominalMass curNode) 
	{
		return edgeMap.get(curNode);
	}

	@Override
	public int getNodeScore(NominalMass node) {
		return nodeScore.get(node);
	}

	@Override
	public int getScore(Peptide pep) {
		int score = 0;
		
		NominalMass prevNode = source;
		int nominalMass = 0;
		for(int i=0; i<pep.size()-1; i++)
		{
			AminoAcid aa;
			if(direction == true)
				aa = pep.get(i);
			else
				aa = pep.get(pep.size()-1-i);
			
			nominalMass += aa.getNominalMass();
			NominalMass curNode = new NominalMass(nominalMass);
			int nodeScore = getNodeScore(curNode);
			int edgeScore = scoredSpec.getEdgeScore(curNode, prevNode, aa.getMass());
			if(prevNode == source && enzyme != null)
			{
				if(enzyme.isCleavable(aa))
					edgeScore += aaSet.getPeptideCleavageCredit();
				else
					edgeScore += aaSet.getPeptideCleavagePenalty();
				score += nodeScore + edgeScore;
				prevNode = curNode;
			}
		}
		if(direction == true)
			nominalMass += pep.get(pep.size()-1).getNominalMass();
		else
			nominalMass += pep.get(0).getNominalMass();
		
		if(nominalMass != pmNode.getNominalMass())
			return Integer.MIN_VALUE;
		else
			return score;
	}

	@Override
	public int getScore(Annotation annotation) {
		int score = getScore(annotation.getPeptide());
		if(score > Integer.MIN_VALUE && enzyme != null)
		{
			AminoAcid neighboringAA;
			if(enzyme.isCTerm())
				neighboringAA = annotation.getPrevAA();
			else
				neighboringAA = annotation.getNextAA();
			if(neighboringAA == null || enzyme.isCleavable(neighboringAA))
				score += aaSet.getNeighboringAACleavageCredit();
			else
				score += aaSet.getNeighboringAACleavagePenalty();
		}
		return score;
	}

	@Override
	public boolean isReverse() {
		return !direction;
	}

	@Override
	public AminoAcidSet getAASet() {
		return aaSet;
	}
	
	private void computeNodeScores() {
		nodeScore = new HashMap<NominalMass,Integer>();
		nodeScore.put(source, 0);
		
		for(int i=1; i<intermediateNodes.size(); i++)
		{
			NominalMass node = intermediateNodes.get(i);
			NominalMass compNode = this.getComplementNode(node);
			int score;
			if(isReverse())
				score = scoredSpec.getNodeScore(compNode, node);
			else
				score = scoredSpec.getNodeScore(node, compNode);
			nodeScore.put(node, score);
		}
		for(NominalMass node : this.sinkNodes)
			nodeScore.put(node, 0);
	}

	private void setForwardEdgesFromSource()
	{
		Location location;
		if(direction)
		{
			if(!this.useProtNTerm)
				location = Location.N_Term;
			else
				location = Location.Protein_N_Term;
		}
		else
		{
			if(!this.useProtCTerm)
				location = Location.C_Term;
			else
				location = Location.Protein_C_Term;
		}
		
		ArrayList<AminoAcid> aaList = aaSet.getAAList(location);
		makeForwardEdges(source, aaList);
	}
	
	private void setForwardEdgesFromIntermediateNodes()
	{
		ArrayList<AminoAcid> aaList = aaSet.getAAList(Location.Anywhere);
		for(int i=1; i<pmNode.getNominalMass(); i++)
			makeForwardEdges(new NominalMass(i), aaList);
	}
	
	private void setBackwardEdgesFromSink()
	{
		Location location;
		if(direction)
		{
			if(!this.useProtCTerm)
				location = Location.C_Term;
			else
				location = Location.Protein_C_Term;
		}
		else
		{
			if(!this.useProtNTerm)
				location = Location.N_Term;
			else
				location = Location.Protein_N_Term;
		}
		
		ArrayList<AminoAcid> aaList = aaSet.getAAList(location);
		
		int peptideNominalMass = pmNode.getNominalMass();
		ArrayList<DeNovoGraph.Edge<NominalMass>> edges = new ArrayList<DeNovoGraph.Edge<NominalMass>>();
		for(AminoAcid aa : aaList)
		{
			NominalMass prevNode = new NominalMass(peptideNominalMass-aa.getNominalMass());
			if(edgeMap.containsKey(prevNode))
			{
				DeNovoGraph.Edge<NominalMass> edge = new DeNovoGraph.Edge<NominalMass>(prevNode, aa.getProbability(), aaSet.getIndex(aa), aa.getMass());				
				edges.add(edge);
			}
		}
		edgeMap.put(pmNode, edges);
	}	
	
	private void makeForwardEdges(NominalMass curNode, ArrayList<AminoAcid> aaList)
	{
		int curNominalMass = curNode.getNominalMass();
		for(AminoAcid aa : aaList)
		{
			int nextNodeNominalMass = curNominalMass+aa.getNominalMass();
			if(nextNodeNominalMass >= pmNode.getNominalMass())
				continue;
			NominalMass nextNode = new NominalMass(nextNodeNominalMass);
			ArrayList<DeNovoGraph.Edge<NominalMass>> edges = edgeMap.get(nextNode);
			if(edges == null)
			{
				edges = new ArrayList<DeNovoGraph.Edge<NominalMass>>();
				edgeMap.put(nextNode, edges);
			}
			
			DeNovoGraph.Edge<NominalMass> edge = new DeNovoGraph.Edge<NominalMass>(
					curNode, 
					aa.getProbability(),
					aaSet.getIndex(aa),
					aa.getMass());
			int errorScore = scoredSpec.getEdgeScore(nextNode, curNode, aa.getMass());
			edge.setErrorScore(errorScore);
			
			edges.add(edge);
		}
	}
	
	private void computeEdgeScores()
	{
		for(NominalMass curNode : intermediateNodes)
		{
			ArrayList<DeNovoGraph.Edge<NominalMass>> edges = edgeMap.get(curNode);
			for(DeNovoGraph.Edge<NominalMass> edge : edges)
			{
				NominalMass prevNode = edge.getPrevNode();
				int errorScore = scoredSpec.getEdgeScore(curNode, prevNode, edge.getEdgeMass());
				edge.setErrorScore(errorScore);
			}
			edgeMap.put(curNode, edges);
		}
	}	

	private void computeCleavageScores()
	{
		// TODO:
	}
}
