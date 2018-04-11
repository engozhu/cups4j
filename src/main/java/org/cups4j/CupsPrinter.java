package org.cups4j;

/**
 * Copyright (C) 2009 Harald Weyhing
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * 
 * See the GNU Lesser General Public License for more details. You should have received a copy of
 * the GNU Lesser General Public License along with this program; if not, see
 * <http://www.gnu.org/licenses/>.
 */

import ch.ethz.vppserver.ippclient.IppResult;
import org.cups4j.ipp.attributes.Attribute;
import org.cups4j.ipp.attributes.AttributeGroup;
import org.cups4j.operations.IppOperation;
import org.cups4j.operations.ipp.*;

import java.io.InputStream;
import java.net.URL;
import java.util.*;

/**
 * Represents a printer on your IPP server
 */

public class CupsPrinter {
  private URL printerURL = null;
  private String name = null;
  private String description = null;
  private String location = null;
  private boolean isDefault = false;
  private String mediaDefault = null;
  private String resolutionDefault = null;
  private String colorModeDefault = null;
  private String sidesDefault = null;

  private String numberUpDefault = null;
  private List<String> numberUpSupported = new ArrayList<String>();
  private List<String> mediaSupported = new ArrayList<String>();
  private List<String> resolutionSupported = new ArrayList<String>();
  private List<String> colorModeSupported = new ArrayList<String>();
  private List<String> mimeTypesSupported = new ArrayList<String>();
  private List<String> sidesSupported = new ArrayList<String>();

  /**
   * Constructor
   * 
   * @param printerURL
   * @param printerName
   * @param isDefault
   *          true if this is the default printer on this IPP server
   */
  public CupsPrinter(URL printerURL, String printerName, boolean isDefault) {
    this.printerURL = printerURL;
    this.name = printerName;
    this.isDefault = isDefault;
  }

  /**
   * Print method
   * 
   * @param printJob
   * @return PrintRequestResult
   * @throws Exception
   */
  public PrintRequestResult print(PrintJob printJob) throws Exception {
    IppOperation command = new IppPrintJobOperation(printerURL.getPort());
    return request(command, printJob);
  }

  private PrintRequestResult request(IppOperation command, PrintJob printJob) throws Exception {
    int ippJobID = -1;
    InputStream document = printJob.getDocument();
    String userName = printJob.getUserName();
    String jobName = printJob.getJobName();
    int copies = printJob.getCopies();
    String pageRanges = printJob.getPageRanges();
    String resolution = printJob.getResolution();

    String pageFormat = printJob.getPageFormat();
    boolean color = printJob.isColor();
    boolean portrait = printJob.isPortrait();

    Map<String, String> attributes = printJob.getAttributes();

    if (userName == null) {
      userName = CupsClient.DEFAULT_USER;
    }
    if (attributes == null) {
      attributes = new HashMap<String, String>();
    }

    attributes.put("requesting-user-name", userName);
    attributes.put("job-name", jobName);

    String copiesString = null;
    StringBuffer rangesString = new StringBuffer();
    if (copies > 0) {// other values are considered bad value by CUPS
      copiesString = "copies:integer:" + copies;
      addAttribute(attributes, "job-attributes", copiesString);
    }
    if (portrait) {
      addAttribute(attributes, "job-attributes", "orientation-requested:enum:3");
    } else {
      addAttribute(attributes, "job-attributes", "orientation-requested:enum:4");
    }

    if (color) {
      addAttribute(attributes, "job-attributes", "output-mode:keyword:color");
    } else {
      addAttribute(attributes, "job-attributes", "output-mode:keyword:monochrome");
    }

    if (pageFormat != null && !"".equals(pageFormat)) {
      addAttribute(attributes, "job-attributes", "media:keyword:" + pageFormat);
    }

    if (resolution != null && !"".equals(resolution)) {
      addAttribute(attributes, "job-attributes", "printer-resolution:resolution:" + resolution);
    }

    if (pageRanges != null && !"".equals(pageRanges.trim()) && !"1-".equals(pageRanges.trim())) {
      String[] ranges = pageRanges.split(",");

      String delimeter = "";

      rangesString.append("page-ranges:setOfRangeOfInteger:");
      for (String range : ranges) {
        range = range.trim();

        String[] values = range.split("-");
        if (values.length == 1) {
          range = range + "-" + range;
        }

        rangesString.append(delimeter).append(range);
        // following ranges need to be separated with ","
        delimeter = ",";
      }
      addAttribute(attributes, "job-attributes", rangesString.toString());
    }

    if (printJob.isDuplex()) {
      addAttribute(attributes, "job-attributes", "sides:keyword:two-sided-long-edge");
    }
    IppResult ippResult = command.request(printerURL, attributes, document);
    PrintRequestResult result = new PrintRequestResult(ippResult);
    // IppResultPrinter.print(result);

    for (AttributeGroup group : ippResult.getAttributeGroupList()) {
      if (group.getTagName().equals("job-attributes-tag")) {
        for (Attribute attr : group.getAttribute()) {
          if (attr.getName().equals("job-id")) {
            ippJobID = Integer.parseInt(attr.getAttributeValue().get(0).getValue());
          }
        }
      }
    }
    result.setJobId(ippJobID);
    return result;
  }

  /**
   * Print method for several print jobs which should be not interrupted by
   * another print job. The printer must support
   * 'multiple-document-jobs-supported' which is a recommended option.
   *
   * @param job1 first print job
   * @param moreJobs more print jobs
   * @return PrintRequestResult
   * @since 0.7.2
   * @author oboehm
   */
  public PrintRequestResult print(PrintJob job1, PrintJob... moreJobs) {
      IppResult ippResult = createPrintJob();
      AttributeGroup attrGroup = ippResult.getAttributeGroup("job-attributes-tag");
      int jobId = Integer.parseInt(attrGroup.getAttribute("job-id").getValue());
      List<PrintJob> printJobs = new ArrayList<PrintJob>();
      printJobs.add(job1);
      printJobs.addAll(Arrays.asList(moreJobs));
      for (int i = 0; i < printJobs.size() - 1; i++) {
          sendDocument(printJobs.get(i), jobId, false);
      }
      return sendDocument(printJobs.get(printJobs.size()), jobId, true);
  }

  private IppResult createPrintJob() {
      Map<String, String> attributes = new HashMap<String, String>();
      IppCreateJobOperation command = new IppCreateJobOperation(printerURL.getPort());
      try {
          IppResult result = command.request(printerURL, attributes);
          validate(result);
          return result;
      } catch (Exception ex) {
          throw new IllegalStateException("cannot create print-job", ex);
      }
  }
  
  private PrintRequestResult sendDocument(PrintJob job, int jobId, boolean lastDocument) {
      IppSendDocumentOperation command = new IppSendDocumentOperation(printerURL.getPort());
      Map<String, String> attributes = job.getAttributes();
      if (attributes == null) {
        attributes = new HashMap<String, String>();
      }
      addAttribute(attributes, "operation-attributes", "job-id:integer:" + jobId);
      addAttribute(attributes, "operation-attributes", "last-document:boolean:" + Boolean.toString(lastDocument));
      job.setAttributes(attributes);
      try {
        PrintRequestResult result = request(command, job);
        validate(result);
        return result;
      } catch (Exception ex) {
          throw new IllegalStateException("cannot send-document for " + job, ex);
      }
  }

  private void validate(IppResult ippResult) {
    if (ippResult.getHttpStatusCode() >= 300) {
      throw new IllegalStateException(
              "IPP request to " + this.printerURL + " was not succesfull: " + ippResult.getHttpStatusResponse());
    }
    validate(new PrintRequestResult(ippResult));
  }

  private void validate(PrintRequestResult result) {
    int statusCode = Integer.decode(result.getResultCode());
    if (statusCode >= 0x0400) {
      throw new IllegalStateException("request to " + this.printerURL + " failed: " + result.getResultCode() + " " +
              result.getResultDescription() + " - " + result.getResultMessage());
    }
  }
  
  /**
   * 
   * @param map
   * @param name
   * @param value
   */
  private void addAttribute(Map<String, String> map, String name, String value) {
    if (value != null && name != null) {
      String attribute = map.get(name);
      if (attribute == null) {
        attribute = value;
      } else {
        attribute += "#" + value;
      }
      map.put(name, attribute);
    }
  }

  /**
   * Get a list of jobs
   * 
   * @param whichJobs
   *          completed, not completed or all
   * @param user
   *          requesting user (null will be translated to anonymous)
   * @param myJobs
   *          boolean only jobs for requesting user or all jobs for this
   *          printer?
   * @return job list
   * @throws Exception
   */

  public List<PrintJobAttributes> getJobs(WhichJobsEnum whichJobs, String user, boolean myJobs) throws Exception {
    IppGetJobsOperation command = new IppGetJobsOperation(printerURL.getPort());

    return command.getPrintJobs(this, whichJobs, user, myJobs);
  }

  /**
   * Get current status for the print job with the given ID.
   * 
   * @param jobID
   * @return job status
   * @throws Exception
   */
  public JobStateEnum getJobStatus(int jobID) throws Exception {
    return getJobStatus(CupsClient.DEFAULT_USER, jobID);
  }

  /**
   * Get current status for the print job with the given ID
   * 
   * @param userName
   * @param jobID
   * @return job status
   * @throws Exception
   */
  public JobStateEnum getJobStatus(String userName, int jobID) throws Exception {
    IppGetJobAttributesOperation command = new IppGetJobAttributesOperation(printerURL.getPort());
    PrintJobAttributes job = command.getPrintJobAttributes(printerURL.getHost(), userName, printerURL.getPort(), jobID);

    return job.getJobState();
  }

  /**
   * Get the URL for this printer
   * 
   * @return printer URL
   */
  public URL getPrinterURL() {
    return printerURL;
  }

  /**
   * Is this the default printer
   * 
   * @return true if this is the default printer false otherwise
   */
  public boolean isDefault() {
    return isDefault;
  }

  protected void setDefault(boolean isDefault) {
    this.isDefault = isDefault;
  }

  /**
   * Get a String representation of this printer consisting of the printer URL
   * and the name
   * 
   * @return String
   */
  public String toString() {
    return name;
  }

  /**
   * Get name of this printer.
   * <p>
   * For a printer http://localhost:631/printers/printername 'printername' will
   * be returned.
   * </p>
   * 
   * @return printer name
   */
  public String getName() {
    return name;
  }

  /**
   * Get location attribute for this printer
   * 
   * @return location
   */
  public String getLocation() {
    return location;
  }

  public void setLocation(String location) {
    this.location = location;
  }

  /**
   * Get description attribute for this printer
   * 
   * @return description
   */
  public String getDescription() {
    return description;
  }

  public List<String> getMediaSupported() {
    return mediaSupported;
  }

  public String getMediaDefault() {
    return mediaDefault;
  }

  public String getResolutionDefault() {
    return resolutionDefault;
  }

  public String getColorModeDefault() {
    return colorModeDefault;
  }

  public List<String> getResolutionSupported() {
    return resolutionSupported;
  }

  public List<String> getColorModeSupported() {
    return colorModeSupported;
  }

  public List<String> getMimeTypesSupported() {
    return mimeTypesSupported;
  }

  public void setPrinterURL(URL printerURL) {
    this.printerURL = printerURL;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public void setMediaDefault(String mediaDefault) {
    this.mediaDefault = mediaDefault;

  }

  public void setMediaSupported(List<String> mediaSupportedList) {
    this.mediaSupported = mediaSupportedList;

  }

  public void setResolutionDefault(String resolutionDefault) {
    this.resolutionDefault = resolutionDefault;

  }

  public void setColorModeDefault(String colorModeDefault) {
    this.colorModeDefault = colorModeDefault;

  }

  public void setResolutionSupported(List<String> resolutionSupported) {
    this.resolutionSupported = resolutionSupported;

  }

  public void setColorModeSupported(List<String> colorModeSupported) {
    this.colorModeSupported = colorModeSupported;

  }

  public void setMimeTypesSupported(List<String> mimeTypesSupported) {
    this.mimeTypesSupported = mimeTypesSupported;

  }

  public String getSidesDefault() {
    return sidesDefault;
  }

  public void setSidesDefault(String sidesDefault) {
    this.sidesDefault = sidesDefault;
  }

  public List<String> getSidesSupported() {
    return sidesSupported;
  }

  public void setSidesSupported(List<String> sidesSupported) {
    this.sidesSupported = sidesSupported;
  }

  public String getNumberUpDefault() {
    return numberUpDefault;
  }

  public void setNumberUpDefault(String numberUpDefault) {
    this.numberUpDefault = numberUpDefault;
  }

  public List<String> getNumberUpSupported() {
    return numberUpSupported;
  }

  public void setNumberUpSupported(List<String> numberUpSupported) {
    this.numberUpSupported = numberUpSupported;
  }

}
