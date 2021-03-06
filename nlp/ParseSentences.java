/*
Collect combining samples and noun phrases from gt sentences,
based on their parse trees.

Input:

fin: input json in the format of [{'raw': 'a dog is sitting on the sofa', 'id': image_id}, ...]
fout: suffix of two output jsons, one contains noun phrases, one contains combining samples
*/

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.Iterator;
 
import java.io.FileReader;
import java.io.FileWriter;
import java.io.StringReader;

import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
//import edu.stanford.nlp.parser.shiftreduce.ShiftReduceParser;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.trees.Tree;

public class ParseSentences {
	public class Tuple<X, Y, Z> {
		public final X x;
		public final Y y;
		public final Z z;
		public Tuple(X x, Y y, Z z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	public static void main(String[] args) throws Exception {
		int curArg = 0;
		String fin = "";
		String fout = "";
		String modelPath = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
		//String modelPath = "edu/stanford/nlp/models/srparser/englishSR.beam.ser.gz";
		String taggerPath = "edu/stanford/nlp/models/pos-tagger/english-bidirectional/english-bidirectional-distsim.tagger";
		while (curArg < args.length) {
			switch (args[curArg]) {
				case "-fin":
					fin = args[curArg + 1];
					curArg += 2;
					break;
				case "-fout":
					fout = args[curArg + 1];
					curArg += 2;
					break;
				default:
					System.err.println("Unknown option");
					System.exit(1);
			} 
		}
		System.err.println("Parsing sentences");		
		ArrayList<Long> ids = new ArrayList<Long>();
		ArrayList<String> sentences = new ArrayList<String>();
		JSONParser json = new JSONParser();
		JSONArray input;
		input = (JSONArray) json.parse(new FileReader(fin));
		for (Object o : input) {
			JSONObject item = (JSONObject) o;
			ids.add((Long)item.get("id"));
			sentences.add((String) item.get("raw"));	
		}

		ArrayList<String> leftseqs = new ArrayList<String>();
		ArrayList<String> rightseqs = new ArrayList<String>();
		ArrayList<String> midseqs = new ArrayList<String>();
		ArrayList<Long> seqids = new ArrayList<Long>();
		ArrayList<Boolean> types = new ArrayList<Boolean>();
		MaxentTagger tagger = new MaxentTagger(taggerPath);
		LexicalizedParser model = LexicalizedParser.loadModel(modelPath);
		//ShiftReduceParser model = ShiftReduceParser.loadModel(modelPath);
		ParseSentences collector = new ParseSentences();
		for (int idx = 0; idx < sentences.size(); ++idx) {
			if (idx % 100 == 0)
				System.err.println(idx + " / " + sentences.size());
			String sentence = sentences.get(idx);
			long id = ids.get(idx);	
			DocumentPreprocessor tokenizer = new DocumentPreprocessor(new StringReader(sentence));
			for (List<HasWord> tokenized_sentence : tokenizer) {
				List<TaggedWord> tagged = tagger.tagSentence(tokenized_sentence);
				Tree tree = model.apply(tagged);
			//	System.out.println(tree);
				collector.collect(null, tree, leftseqs, rightseqs, midseqs, seqids, types, id);
				break;
			}
		}
		JSONArray nounseqlist = new JSONArray();	
		JSONArray combineseqlist = new JSONArray();	
		for (int i = 0; i < leftseqs.size(); ++i) { 
			JSONObject obj = new JSONObject();
			obj.put("left", leftseqs.get(i));
			obj.put("mid", midseqs.get(i));
			obj.put("right", rightseqs.get(i));
			obj.put("sentid", new Long(seqids.get(i)));
			if (types.get(i))
				nounseqlist.add(obj);
			else
				combineseqlist.add(obj);
		}
			
		FileWriter file = new FileWriter("nounseq." + fout);
		file.write(nounseqlist.toJSONString());
		file.flush();
		FileWriter file2 = new FileWriter("combineseq." + fout);
		file2.write(combineseqlist.toJSONString());
		file2.flush();	
	}	

	public String getLabel(Tree node) {
		return node.label().value();
	}

	public Tuple<String, String, Boolean> collect(Tree parent, Tree node, ArrayList<String> leftseqs, ArrayList<String> rightseqs, ArrayList<String> midseqs, ArrayList<Long> seqids, ArrayList<Boolean> types, long id) {
		if (node.firstChild() == null) 
			return new Tuple<String, String, Boolean>(getLabel(node), "", false);
		int numChild = node.numChildren();
		List<Tree> children = node.getChildrenAsList();
		String label = getLabel(children.get(numChild - 1));
		String mid = "";
		String left = "";
		String right = "";
		Tuple<String, String, Boolean> out; 
		if (label.startsWith("NN")) {
			for (int i = 0; i < numChild - 1; ++i) {
				out = collect(node, children.get(i), leftseqs, rightseqs, midseqs, seqids, types, id);
				assert !out.z;
				assert out.y == "";
				mid += mid == "" ? out.x : " " + out.x; 
			}
			out = collect(node, children.get(numChild - 1), leftseqs, rightseqs, midseqs, seqids, types, id);
			right = out.x;
			leftseqs.add(left);
			rightseqs.add(right);
			midseqs.add(mid);
			seqids.add(id);
			types.add(true);
			String y = left;
			if (mid != "")
				y += y == "" ? mid : " " + mid;
			y += y == "" ? right : " " + right;
			return new Tuple<String, String, Boolean>("", y, true);
		}
		mid = "";
		left = "";
		right = "";
		String prefix = "";
		boolean contain_noun = false;
		for (Tree child : children) {
			out = collect(node, child, leftseqs, rightseqs, midseqs, seqids, types, id);
			if (out.z) {
				if (contain_noun) {
					String y = mid;
					if (out.x != "")
						y += y == "" ? out.x : " " + out.x;
					leftseqs.add(left);
					midseqs.add(y);
					rightseqs.add(out.y);	
					seqids.add(id);
					types.add(false);
					y += y == "" ? out.y : " " + out.y;	
					left += left == "" ? y : " " + y;
					mid = "";
				} else {
					left = out.y;
					if (out.x != "")
						prefix += prefix == "" ? out.x : " " + out.x;
				}
			} else if (contain_noun)
				mid += mid == "" ? out.x : " " + out.x;
			else
				prefix += prefix == "" ? out.x : " " + out.x;
			contain_noun = contain_noun || out.z;
		}
		if (mid != "" && contain_noun) {
			leftseqs.add(left);
			midseqs.add(mid);
			rightseqs.add("");
			seqids.add(id);
			types.add(false);
			left += left == "" ? mid : " " + mid;
		}
		if (parent == null && prefix != "") {
			leftseqs.add("");
			midseqs.add(prefix);
			rightseqs.add(left);
			seqids.add(id);
			types.add(false);
		}
		return new Tuple<String, String, Boolean>(prefix, left, contain_noun);		
	}

}
