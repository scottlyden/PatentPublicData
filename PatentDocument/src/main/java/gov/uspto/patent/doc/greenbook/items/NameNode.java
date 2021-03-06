package gov.uspto.patent.doc.greenbook.items;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.naming.directory.InvalidAttributesException;

import org.dom4j.DocumentHelper;
import org.dom4j.Node;
import org.dom4j.XPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

import gov.uspto.parser.dom4j.ItemReader;
import gov.uspto.patent.InvalidDataException;
import gov.uspto.patent.model.entity.Name;
import gov.uspto.patent.model.entity.NameOrg;
import gov.uspto.patent.model.entity.NamePerson;

public class NameNode extends ItemReader<Name> {
	private static final Logger LOGGER = LoggerFactory.getLogger(NameNode.class);

	private static final XPath NAMEXP = DocumentHelper.createXPath("NAM");

	public static final Set<String> PERSON_SUFFIXES = new HashSet<String>(
			Arrays.asList("PHD", "ESQ", "J.D", "MR", "MRS", "M.D", "DR", "P.L", "P.E", "JR", "SR", "I", "II", "III",
					"IV", "V", "1ST", "2ND", "3RD", "4TH", "5TH", "1", "2", "3", "4", "5"));

	public static final Set<String> PERSON_LONG_SUFFIXES = new HashSet<String>(Arrays.asList("PH.D", "ADMINISTRATOR",
			"ADMINSTRATOR", "ADMINISTRATOR AND EXECUTOR", "ADMINISTRATOR BY", "ADMINISTRATORS",
			"ADMINISTRATRIX/EXECUTRIX", "ADMINISTRATRIX", "AMINISTRATRIX", "AGENT", "PATENT AGENT", "PAT. AGENT",
			"ASSOC", "ASSICIATE", "ATTY", "ATTORNEY", "PATENT ATTORNEY", "PAT. ATTY", "CO-EXECUTRIX", "COEXECUTRIX",
			"EXECTRIX", "EXECUTOR", "EXECUTER", "EXECUTORS", "EXECUTOR OF ESTATE", "EXECUTRIX", "ESQUIRE",
			"LEGAL GUARDIAN", "GUARDIAN", "HEIR", "HEIR AND LEGAL SUCCESSOR", "HEIRS", "HEIRS-AT-LAW", "HEIR-AT-LAW",
			"HEIR AT LAW", "HEIRESS", "COEXECUTOR", "CO-EXECUTOR", "INHERITOR", "LEGAL AUTHORIZED HEIR", "LEGAL HEIR",
			"LEGAL REPRESENTATIVE", "LEGAL REPRESENTIVE", "A LEGAL REPRESENTATIVE", "LEGAL REPRESENTATIVE AND HEIR",
			"SUCCESSOR", "SOLE BENEFICIARY", "SOLE HEIR", "REPRESENTATIVE", "PERSONAL REPRESENTATIVE",
			"PERSONAL REPRESENTATIVE OF THE ESTATE", "JOINT PERSONAL REPRESENTATIVE", "SURVIVING SPOUSE",
			"SPECIAL ADMINISTRATOR", "TRUST", "TRUSTEE", "TRUSTEE OR SUCCESSOR TRUSTEE", "DECEASED", "DECESASED",
			"LEGAL", "LEGALESS", "IV ESQ", "JR. DECEASED", "JR. II", "JR. ESQ", "JR. ATTY", "JR. EXECUTOR",
			"JR. CO-EXECUTOR", "SR. DECEASED", "JR. HEIR"));

	public static final Set<String> COMPANY_SUFFIXES = new HashSet<String>(
			Arrays.asList("INCORPORATED", "INC", "LLC", "L.L.C", "LTD", "LTD PLC", "PLC", "P.L.C", "L.C", "LC", "LLP",
					"L.L.P", "P.L.L.C", "PLLC", "S.C", "P.A", "PA", "P.C", "PC", "P.L", "P.S", "S.P.A", "S.P.C", "CHTD",
					"L.P.A", "IP GROUP", "INTELLECTUAL PROPERTY PRACTICE GROUP", "GROUP", "COMPANY",
					"A PROFESSIONAL CORP", "A PROF. CORP", "CORP", "PATENT & TRADEMARK ATTORNEYS"));

	public static final Set<String> PERSON_FORMERLY = new HashSet<String>(
			Arrays.asList("NEE", "BORN", "FORMERLY", "WIDOW", "BY CHANGE OF NAME", "NOW BY CHANGE OF NAME", "A/K/A",
					"ALSO KNOWN AS", "EXECUTRIX ALSO KNOWN AS"));

	public static final Set<String> PER_SUFFIX_STARTS = new HashSet<String>(
			Arrays.asList("BY SAID", "PRESIDENT", "ADMINISTRATOR OF", "EXECUTOR OF ESTATE OF"));

	private static final int longNameLen = 18;

	private static final Pattern LN_CLEAN = Pattern.compile(",? deceased\\b");
	private static final Pattern FN_CLEAN = Pattern.compile("^by ");
	private static final Pattern LN_COMMA_FIX = Pattern.compile("([a-z]) (nee|born|formerly|widow|also known as) ");

	public NameNode(Node itemNode) {
		super(itemNode);
	}

	@Override
	public Name read() {
		Node nameN = NAMEXP.selectSingleNode(itemNode);
		String fullName = nameN != null ? nameN.getText().trim() : null;
		if (fullName == null) {
			return null;
		}

		try {
			return createName(fullName);
		} catch (InvalidDataException e) {
			return null;
		}
	}

	protected String[] suffixFix(String lastName) {

		String lastWord = lastName.substring(lastName.lastIndexOf(' ') + 1).replaceFirst("\\.$", "").replace(",", "")
				.trim().toUpperCase();
		if (COMPANY_SUFFIXES.contains(lastWord)) {
			return new String[] { "org", lastName, lastWord };
		}

		List<String> parts = Splitter.onPattern(",").limit(2).trimResults().splitToList(lastName);

		if (parts.size() == 2) {
			String lastCommaWord = lastName.substring(lastName.lastIndexOf(',') + 1).replaceFirst("\\.$", "")
					.replace(",", "").trim().toUpperCase();
			String suffix = parts.get(1);
			String suffixCheck = suffix.replaceFirst("\\.$", "").replace(",", "").toUpperCase();

			if (COMPANY_SUFFIXES.contains(suffixCheck) || COMPANY_SUFFIXES.contains(lastCommaWord)) {
				return new String[] { "org", parts.get(0), suffix };
			} else if ((suffixCheck.length() < 4 && PERSON_SUFFIXES.contains(suffixCheck))
					|| PERSON_LONG_SUFFIXES.contains(suffixCheck)) {
				LOGGER.debug("Suffix Fixed, common suffix '{}' from lastname: '{}' -> '{}'", suffixCheck, lastName,
						parts.get(0));
				return new String[] { "per", parts.get(0), suffix };
			} else if (PERSON_FORMERLY.stream().anyMatch(s -> suffixCheck.startsWith(s + " "))) {
				String matched = PERSON_FORMERLY.stream().filter(s -> suffixCheck.startsWith(s + " ")).findFirst()
						.orElse("");
				String synonym = suffix.substring(matched.length() + 1);
				String[] synCheck = suffixFix(synonym);
				String synname = synCheck != null ? synCheck[1] : synonym;
				LOGGER.debug("Suffix Fixed '{}' [{}] from lastname: '{}' -> '{}'", suffixCheck, matched, synname,
						parts.get(0));
				return new String[] { "per-syn", parts.get(0), suffix, synname };
			} else if (PER_SUFFIX_STARTS.stream().anyMatch(s -> suffixCheck.startsWith(s + " "))) {
				LOGGER.debug("Suffix Fixed '{}' from lastname: '{}' -> '{}'", suffixCheck, lastName, parts.get(0));
				return new String[] { "per", parts.get(0), suffix };
			} else {
				LOGGER.info("Unmatched Suffix: '{}' from lastname: '{}'", suffix, lastName);
			}
		}

		return null;
	}

	/**
	 * Parse string containing Full Name, break into name parts and build Name
	 * object.
	 * 
	 * @param fullName
	 * @return
	 * @throws InvalidAttributesException
	 */
	public Name createName(String fullName) throws InvalidDataException {
		if (fullName == null || fullName.trim().isEmpty()) {
			throw new InvalidDataException("Name is missing");
		}

		List<String> nameParts = Splitter.onPattern(";").limit(2).trimResults().splitToList(fullName);

		Name entityName = null;
		if (nameParts.size() == 2) {
			String lastName = nameParts.get(0);
			String firstName = nameParts.get(1);

			if ((firstName.length() > longNameLen || lastName.length() > longNameLen)
					&& (isOrgName(firstName) || isOrgName(lastName))) {
				return new NameOrg(fullName);
			}

			firstName = FN_CLEAN.matcher(firstName).replaceFirst("");
			lastName = LN_CLEAN.matcher(lastName).replaceFirst("");
			lastName = LN_COMMA_FIX.matcher(lastName).replaceFirst("$1, $2 ");

			// long commaCount = fullName.trim().chars().filter(c -> c == ',').count();
			// long spaceCount = fullName.trim().chars().filter(c -> c == ' ').count();

			if (lastName.contains(",")) {
				String[] parts = suffixFix(lastName);
				if (parts != null && "per".equals(parts[0])) {
					lastName = parts[1];
					entityName = new NamePerson(firstName, lastName);
					entityName.setSuffix(parts[2]);
				} else if (parts != null && "org".equals(parts[0])) {
					lastName = parts[1];
					entityName = new NameOrg(fullName);
					entityName.setSuffix(parts[2]);
				} else if (parts != null && "per-syn".endsWith(parts[0])) {
					lastName = parts[1];
					String suffix = parts[2];
					String synonym = parts[3];
					if (synonym.contains(firstName)) {
						synonym = synonym.replace(firstName, "");
					}
					entityName = new NamePerson(firstName, lastName);
					entityName.setSuffix(suffix);
					entityName.addSynonym(synonym + ", " + firstName);
					entityName.addSynonym(synonym + ", " + firstName.subSequence(0, 1) + ".");
				} else {
					entityName = new NameOrg(fullName);
				}
			} else {
				entityName = new NamePerson(firstName, lastName);
			}

		} else {
			entityName = new NameOrg(fullName);
		}

		return entityName;
	}

	public boolean isOrgName(String name) {
		String[] ret = suffixFix(name);
		if (ret != null && "org".equals(ret[0])) {
			// Use of: & and ;; counts of space and commas
			return true;
		}
		return false;
	}
}
