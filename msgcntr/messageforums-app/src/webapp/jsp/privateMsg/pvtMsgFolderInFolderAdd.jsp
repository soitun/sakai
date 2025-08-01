<%@ taglib uri="http://java.sun.com/jsf/html" prefix="h" %>
<%@ taglib uri="http://java.sun.com/jsf/core" prefix="f" %>
<%@ taglib uri="http://sakaiproject.org/jsf2/sakai" prefix="sakai" %>
<jsp:useBean id="msgs" class="org.sakaiproject.util.ResourceLoader" scope="session">
   <jsp:setProperty name="msgs" property="baseName" value="org.sakaiproject.api.app.messagecenter.bundle.Messages"/>
</jsp:useBean>

<f:view>
  <sakai:view title="#{msgs.pvt_msgs_label} #{msgs.pvt_create_folder}">
<!--jsp/privateMsg/pvtMsgFolderInFolderAdd.jsp-->
		<h:form id="pvtMsgFolderAdd">
		       		<script>includeLatestJQuery("msgcntr");</script>
       		<script src="/messageforums-tool/js/sak-10625.js"></script>
			<script src="/messageforums-tool/js/messages.js"></script>

			<div class="page-header">
				<sakai:tool_bar_message value="#{msgs.pvt_msgs_label} #{msgs.pvt_create_folder}" />
			</div>
				<div class="instruction">
				  <h:outputText value="#{msgs.cdfm_required}"/> <h:outputText value="#{msgs.pvt_star}" styleClass="reqStarInline"/>
				</div>
			   
			   <h:messages styleClass="alertMessage" id="errorMessages" rendered="#{! empty facesContext.maximumSeverity}" />
			  
		  <h:panelGrid styleClass="jsfFormTable" columns="2">
			  <h:panelGroup styleClass="shorttext required">
						  <h:outputLabel for="title" >
						   <h:outputText value="#{msgs.pvt_star}" styleClass="reqStar"/>
						  <h:outputText value="#{msgs.pvt_folder_title}"/>
						  </h:outputLabel>
						  </h:panelGroup>
			  <h:panelGroup styleClass="shorttext">
						  <h:inputText id="title" value="#{PrivateMessagesTool.addFolder}" />
						  </h:panelGroup>
						  </h:panelGrid>
			  
			     
				<sakai:button_bar>
			  	  <h:commandButton action="#{PrivateMessagesTool.processPvtMsgFldInFldCreate}" value="#{msgs.pvt_add}" accesskey="s" styleClass="active" />
			    <h:commandButton action="#{PrivateMessagesTool.processPvtMsgReturnToFolderView}" value="#{msgs.pvt_cancel}" accesskey="x" />
			  </sakai:button_bar>   
           
		 </h:form>

	</sakai:view>
</f:view>



