import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import com.ephesoft.dcma.script.IJDomScript;
import com.ephesoft.dcma.util.logger.EphesoftLogger;
import com.ephesoft.dcma.util.logger.ScriptLoggerFactory;

/*
 * @author AIFoundry Professional Services (Sumit Dadhwal)
 * @version 3.1
 * @The program assembles the individual pages in the workflow once they are classified by the OCR.
 * @Modifications Overview - Introduced custom logic(functions) to enhance the system accuracy post OCR classification. Details:
 *  @@KeyWord Classification - Look for page/document specific phrases in the OCR output to narrow down the classification results.
 * 	@@Merging Non Classified pages - Combine all the non classified pages into one page/document to enhance the workflow effieciency & reduce 
	the manual validation time by 50%. 
	@@Mark Other Document as Auto Review - Flag the unwanted pages/document as "Other" to automate the manual review. Other documents will be processed straight 
	through the system without any manual review. Average effieciency(time) increase per loan 30%.  
 *
 */

public class ScriptDocumentAssembler implements IJDomScript {

	private static EphesoftLogger LOGGER = ScriptLoggerFactory.getLogger(ScriptDocumentAssembler.class);

	public Object execute(Document documentFile, String methodName, String documentIdentifier) {
		Exception exception = null;
		try {

			if (null == documentFile) {
				LOGGER.error("Input document is null.");
			}

			LOGGER.info("*************  Inside ScriptDocumentAssembler scripts.");
			LOGGER.info("*************  Start execution of the ScriptDocumentAssembler scripts.");

			keyWordClassification(documentFile);
			mergeOtherDocuments(documentFile);
			setOthersToAutoReview(documentFile);

			LOGGER.info("*************  End execution of the ScriptDocumentAssembler scripts.");

		} catch (Exception e) {
			LOGGER.error("*************  Error occurred in scripts." + e.getMessage());
			exception = e;
			e.printStackTrace();
		}
		return exception;
	}

	/*
	keyWordClassification - Look for page/document specific phrases to narrow down the classification results. Examine MICR content for Bank Cheques.
	Input: Document Object - XML representation of the document.
	Output: Set document type, confidence, and description if regular expression match is found.
	Modification History: 
			02/10/2018 (Sumit Dadhwal) - Initial version.
			02/13/2018 (Sumit Dadhwal) - Added "Bank Cheques" MICR content validation.
	*/
	private void keyWordClassification(Document documentFile) throws JDOMException, IOException {

		String PS = "\\";
		Element ROOT = documentFile.getRootElement();
		String BLP = ROOT.getChildText("BatchLocalPath");
		String BC = ROOT.getChildText("BatchClassIdentifier");
		String BI = ROOT.getChildText("BatchInstanceIdentifier");
		Properties PROP = new Properties();
		//Properties file contains the master "Phrases" for document types.
		String PATH = BLP.replaceFirst("ephesoft-system-folder", "") + BC + PS + "script-config" + PS
				+ "KeyWordClassifyConfig.properties";
		String CON = new Scanner(new File(PATH)).useDelimiter("\\Z").next();
		PROP.load(new StringReader(CON.replace(PS, PS + PS)));
		//All Bank Cheques contain a combination of the three MICR keys; Routing Number, Account Number, and Cheque Number. The format of the routing number key is a[123456]a. Numeric digits can be of length 6 to 9. 
		String MICRPatternKey = "a[0-9]{6,9}a";
		
		LOGGER.error("Document Assembler Script: Keyword based Classification");
			List<Element> DL = ROOT.getChild("Documents").getChildren("Document");
			for (Element D : DL) {
				List<Element> PL = D.getChild("Pages").getChildren("Page");
				for (Element P : PL) {
					String HFN = P.getChildText("HocrFileName");
					String HP = BLP + PS + BI + PS + HFN;
					SAXBuilder sb = new SAXBuilder();
					Document PD = sb.build(HP);
					Element PR = PD.getRootElement();
					String C = PR.getChild("HocrPage").getChildText("HocrContent");
					if (C != null) {
						int CL = C.length();
						//IF the document contains less than 200 characters, mark it as OTHER.
						if (CL <= 200) {
							D.getChild("Type").setText("Other");
							D.getChild("Description").setText("Other");
							D.getChild("Confidence").setText("100");
							break;
						} else {
							Enumeration E = PROP.propertyNames();
							while (E.hasMoreElements()) {
								String key = (String) E.nextElement();
								String R = PROP.getProperty(key);
								Pattern PAT = Pattern.compile(R);
								Matcher M = PAT.matcher(C);
								if (M.find()) {
									LOGGER.error("Document Assembler Script. Keyword match found - key: " + key);
									D.getChild("Type").setText(key);
									D.getChild("Description").setText(key);
									D.getChild("Confidence").setText("100.0");
									break;
								}
							}

							//Check For MICR Field (Bank Cheques validation)
							List<Element> PLF = P.getChild("PageLevelFields").getChildren("PageLevelField");
							for(Element PF : PLF){
								
								Element PF_Type = PF.getChild("Type");
								//Check for the "MICR" tag
								if(PF_Type != null && PF.getChildText("Type").equals("MICR")){
									
									String PF_Type_Val = PF.getChildText("Value");
									if(PF_Type_Val.length() > 0){
										Pattern MICR_Pattern = Pattern.compile(MICRPatternKey);
										Matcher MICR_Matcher = MICR_Pattern.matcher(PF_Type_Val);
										if (MICR_Matcher.find()) {
											LOGGER.error("MICR match found. MICR String: " + PF_Type_Val);
											D.getChild("Type").setText("Check");
											D.getChild("Description").setText("Check");
											D.getChild("Confidence").setText("100.0");
											break;
										}
									}	
									
								}									
							}
							if (D.getChildText("Type") == "Other") {
								D.getChild("Confidence").setText("100");
								break;
							}
							
						
						}	
					}
						
				}
			}

	}
	
	
	/*
	mergeOtherDocuments - Combine all the non classified pages into one page/document.
	Input: Document Object - XML representation of the document.
	Output: "Other" document.
	Modification History: 
			02/10/2018 (Sumit Dadhwal) - Initial version.
	*/
	private void mergeOtherDocuments(Document documentFile) throws JDOMException, IOException {

		String PS = "\\";
		Element ROOT = documentFile.getRootElement();
		String BLP = ROOT.getChildText("BatchLocalPath");
		String BC = ROOT.getChildText("BatchClassIdentifier");
		String BI = ROOT.getChildText("BatchInstanceIdentifier");
		
		List<Element> docList = ROOT.getChild("Documents").getChildren("Document");
		for (int i = 0; i < docList.size() - 1; i++) {

			Element firstDoc = docList.get(i);
			String firstDocType = firstDoc.getChildText("Type");

			Element secondDoc = docList.get(i + 1);
			String secondDocType = secondDoc.getChildText("Type");

			if ((firstDocType.equals("Other") && secondDocType.equals("Other")) || (firstDocType.equals("HUD_27011") && secondDocType.equals("HUD_27011"))) {
					LOGGER.error("Merge Other Docs");
					mergeDocumentBetween(firstDoc, secondDoc);
					docList = ROOT.getChild("Documents").getChildren("Document");
					i--;
			}
		}
		reassignDocIdentifier(documentFile);				
	}
	
	private void mergeDocumentBetween(Element document, Element nextDocument) {

		Element pages = document.getChild("Pages");
		Element nextDocumentPages = nextDocument.getChild("Pages");
		List<?> pageList = nextDocumentPages.getChildren("Page");
		for (int i = 0; i < pageList.size(); i++) {
			Element duplicatePage = (Element) ((Element) pageList.get(i)).clone();
			pages.addContent(duplicatePage);
		}
		nextDocument.detach();
	}

	private void reassignDocIdentifier(Document documentFile) {
		Element ROOT = documentFile.getRootElement();
		List<Element> docList = ROOT.getChild("Documents").getChildren("Document");
		for (int docIndex = 0; docIndex < docList.size(); docIndex++) {
			Element document = (Element) docList.get(docIndex);
			Element identifier = document.getChild("Identifier");
			int documentIndex = docIndex + 1;
			identifier.setText("DOC" + documentIndex);
		}
	}
	
	/*
	setOthersToAutoReview - Flag the "Other" document for straight through processing.
	Input: Document Object - XML representation of the document.
	Output: Set "Other" document reviewed tag to true.
	Modification History: 
		02/10/2018 (Sumit Dadhwal) - Initial version.
	*/
		private void setOthersToAutoReview(Document documentFile) {
		
		Element ROOT = documentFile.getRootElement();
		List<Element> docList = ROOT.getChild("Documents").getChildren("Document");
		for (Element doc : docList){
		
			String docName = doc.getChildText("Type");
			if (docName.equals("Other")){
				Element conf = doc.getChild("Confidence");
				Element confThreshold = doc.getChild("ConfidenceThreshold");
				Element reviewed = doc.getChild("Reviewed");
				conf.setText("100.0");
				reviewed.setText("true");
			}
		}
	}
}