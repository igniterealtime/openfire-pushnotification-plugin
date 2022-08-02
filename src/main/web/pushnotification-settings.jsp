<%@ page contentType="text/html; charset=UTF-8" %>

<%@ page import="org.jivesoftware.util.*" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.Map" %>
<%@ page import="org.igniterealtime.openfire.plugins.pushnotification.PushInterceptor" %>

<%@ taglib uri="admin" prefix="admin" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>

<jsp:useBean id="webManager" class="org.jivesoftware.util.WebManager" />
<% webManager.init(request, response, session, application, out ); %>
<%
    boolean save = request.getParameter("save") != null;
    boolean success = false;
    Map<String, String> errors = new HashMap<>();

    if (save) {
        Cookie csrfCookie = CookieUtils.getCookie(request, "csrf");
        String csrfParam = ParamUtils.getParameter(request, "csrf");

        if (csrfCookie == null || csrfParam == null || !csrfCookie.getValue().equals(csrfParam)) {
            errors.put("csrf", "CSRF checksum failed. Reload the page and try again.");
        } else {
            final int maxPerSecondAttribute = ParamUtils.getIntParameter(request, "max-per-second", PushInterceptor.MAX_PER_SECOND.getDefaultValue());
            final boolean summaryEnableAttribute = ParamUtils.getBooleanParameter(request, "summary.enable");
            final boolean summaryIncludeLastSenderAttribute = ParamUtils.getBooleanParameter(request, "summary.include-last-sender");
            final boolean summaryIncludeLastMessageBodyAttribute = ParamUtils.getBooleanParameter(request, "summary.include-last-message-body");

            PushInterceptor.MAX_PER_SECOND.setValue(maxPerSecondAttribute);
            PushInterceptor.SUMMARY_ENABLE.setValue(summaryEnableAttribute);
            PushInterceptor.SUMMARY_INCLUDE_LAST_SENDER.setValue(summaryIncludeLastSenderAttribute);
            PushInterceptor.SUMMARY_INCLUDE_LAST_MESSAGE_BODY.setValue(summaryIncludeLastMessageBodyAttribute);

            webManager.logEvent("Updated push notification settings", "max per second = "+maxPerSecondAttribute+",\nsummary enable = "+summaryEnableAttribute+",\nsummary include last sender = "+summaryIncludeLastSenderAttribute+",\nsummary include last message body = " + summaryIncludeLastMessageBodyAttribute);

            success = true;
        }
    }

    String csrfParam = StringUtils.randomString(15);
    CookieUtils.setCookie(request, response, "csrf", csrfParam, -1);
    pageContext.setAttribute("csrf", csrfParam);
    pageContext.setAttribute("errors", errors);
    pageContext.setAttribute("success", success);
    pageContext.setAttribute("maxPerSecond", PushInterceptor.MAX_PER_SECOND.getValue());
    pageContext.setAttribute("summeryEnable", PushInterceptor.SUMMARY_ENABLE.getValue());
    pageContext.setAttribute("summeryIncludeLastSender", PushInterceptor.SUMMARY_INCLUDE_LAST_SENDER.getValue());
    pageContext.setAttribute("summeryIncludeLastMessageBody", PushInterceptor.SUMMARY_INCLUDE_LAST_MESSAGE_BODY.getValue());
%>

<html>
<head>
    <title>
        <fmt:message key="pushnotification.settings.title"/>
    </title>
    <meta name="pageID" content="pushnotification-settings"/>
</head>
<body>

<c:choose>
    <c:when test="${not empty errors}">
        <c:forEach var="err" items="${errors}">
            <admin:infobox type="error">
                <c:choose>
                    <c:when test="${err.key eq 'csrf'}"><fmt:message key="global.csrf.failed" /></c:when>
                    <c:otherwise><fmt:message key="pushnotification.settings.error" />: <c:out value="${err.key}"/>! <c:out value="${err.value}"/></c:otherwise>
                </c:choose>
            </admin:infobox>
        </c:forEach>
    </c:when>
    <c:when test="${success}">
        <admin:infobox type="success">
            <fmt:message key="pushnotification.settings.saved_successfully" />
        </admin:infobox>
    </c:when>
</c:choose>

<p><fmt:message key="pushnotification.settings.description.detail" /></p>
<br />

<form action="pushnotification-settings.jsp" method="post">
    <input type="hidden" name="csrf" value="${csrf}" />
    <div class="jive-contentBoxHeader">
        <fmt:message key="pushnotification.settings.boxtitle" />
    </div>
    <div class="jive-contentBox">
        <table cellspacing="0" border="0">
            <tbody>
            <tr>
                <td>
                    <input type="number" min="0" name="max-per-second" id="max-per-second" value='${maxPerSecond}' />
                </td>
                <td style="vertical-align:top">
                    <label for="max-per-second"><fmt:message key="pushnotification.settings.max-per-second.label" /></label>
                </td>
            </tr>
            <tr>
                <td>
                    <input type="checkbox" name="summary.enable" id="summary.enable" ${summeryEnable ? "checked" : ""}
                       onchange="document.getElementById('summary.include-last-sender').parentElement.parentElement.style.visibility = (document.getElementById('summary.enable').checked ? 'visible' : 'hidden'); document.getElementById('summary.include-last-message-body').parentElement.parentElement.style.visibility = (document.getElementById('summary.enable').checked ? 'visible' : 'hidden')"
                    />
                </td>
                <td style="vertical-align:top">
                    <label for="summary.enable"><fmt:message key="pushnotification.settings.summary.enable.label" /></label>
                </td>
            </tr>
            <tr style="visibility: ${summeryEnable ? 'visible' : 'hidden'};">
                <td>
                    <input type="checkbox" name="summary.include-last-sender" id="summary.include-last-sender" ${summeryIncludeLastSender ? "checked" : ""} />
                </td>
                <td style="vertical-align:top">
                    <label for="summary.include-last-sender"><fmt:message key="pushnotification.settings.summary.include-last-sender.label" /></label>
                </td>
            </tr>
            <tr style="visibility: ${summeryEnable ? 'visible' : 'hidden'};">
                <td>
                    <input type="checkbox" name="summary.include-last-message-body" id="summary.include-last-message-body" ${summeryIncludeLastMessageBody ? "checked" : ""} />
                </td>
                <td style="vertical-align:top">
                    <label for="summary.include-last-message-body"><fmt:message key="pushnotification.settings.summary.include-last-message-body.label" /></label>
                </td>
            </tr>
            </tbody>
        </table>
        <p><fmt:message key="pushnotification.settings.privacy-warning"/></p>
    </div>
    <button type="submit" name="save">
        <fmt:message key="global.save" />
    </button>
</form>

</body>
</html>
