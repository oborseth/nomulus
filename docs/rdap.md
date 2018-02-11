# RDAP user's guide

[RDAP](https://www.icann.org/rdap) is a JSON REST protocol, served over HTTPS,
for retrieving registry information. It returns data similar to the WHOIS
service, but in a JSON-structured format. This document describes the Nomulus
system's support for the RDAP protocol.

## Quick example <a id="quick_example"></a>

RDAP information is available via regular Web queries. For example, if your App
Engine project ID is `project-id`, and `tld` is a TLD managed by that instance
of Nomulus, enter the following in a Web browser:

```
    https://project-id.appspot.com/rdap/domains?name=*.tld
```

You should get back a long string of apparent JSON gobbledygook, listing the
first 100 domains under that TLD. There are a number of online JSON formatters;
paste the result string into one of them, and you should be able to scroll
through and see the information. You can also use the Chrome browser developer
console's network pane, then send the request and look at the Preview tab to see
the response in an expandable tree format.

## Introduction to RDAP <a id="introduction"></a>

RDAP is a next-generation protocol for dissemination of registry data. It is
eventually intended to replace the WHOIS protocol. RDAP was defined in 2015 in a
series of RFCs:

*   [RFC 7480: HTTP Usage in the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7480)
*   [RFC 7481: Security Services for the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7481)
*   [RFC 7482: Registration Data Access Protocol (RDAP) Query
    Format](https://tools.ietf.org/html/rfc7482)
*   [RFC 7483: JSON Responses for the Registration Data Access Protocol
    (RDAP)](https://tools.ietf.org/html/rfc7483)
*   [RFC 7484: Finding the Authoritative Registration Data (RDAP)
    Service](https://tools.ietf.org/html/rfc7484)

Using RDAP, users can send in standard HTTP requests with specific URLs (such as
`.../rdap/domain/example.com` to get information about the domain `example.com`,
or `.../rdap/domains?name=ex*.com` to get information about domains beginning
with `ex` and having the TLD `.com`), and receive back a JSON response
containing the requested data. The data is more or less the same information
that WHOIS provides today, but formatted in a more standardized and
machine-readable manner.

ICANN is sponsoring a one-year [RDAP Pilot
Program](https://www.icann.org/news/announcement-2017-09-05-en), allowing
registries to implement RDAP and, if desired, make modifications to the protocol
to support desired extra features. Nomulus is participating in this program. The
experimental extra features supported by Nomulus are described later.

## Nomulus RDAP request endpoints <a id="endpoints"></a>

The suite of URL endpoint paths is listed below. The paths should be tacked onto
the usual App Engine server name. For example, if the App Engine project ID is
`project-id`, the full path for a domain lookup of domain iam.soy would be:

```
    https://project-id.appspot.com/rdap/domain/iam.soy
```

The search endpoints (those with a query string) can return more than one match.
The maximum result set size is limited by a configuration parameter, currently
set to 100.

### /rdap/autnum/ <a id="autnum"></a>

The RDAP protocol defines an autnum endpoint, but autonomous system numbers are
not supported by registries, so queries of this type will always return an
error.

### /rdap/domain/ <a id="domain"></a>

Look up a single domain by name. The full domain name is specified at the end of
the path.

```
    /rdap/domain/abc.tld
```

### /rdap/domains? <a id="domains"></a>

Search for one or more domains. The RDAP specification supports three kinds of
searches: by domain name, by nameserver name, or by nameserver IP address. It
defines a wildcard syntax, but allows the server to limit how wildcards are
used; Nomulus' wildcard rules are described below.

A maximum of 100 domains will be returned in response to a single query. If more
than one domain is returned, only data about the domain itself is included. If a
single domain is returned, associated nameservers and contacts are also returned
(except that requests not authenticated as associated with the registrar of the
domain will not see contact information).

#### Search by domain name

Domain searches by domain name use the `name` query parameter. The query value
can be a domain name without wildcards, in which case the results will be the
same as a lookup of that name.

```
    /rdap/domains?name=abc.tld
```

The query value can include a wildcard at the end of the string. There must be
at least two characters before the wildcard, to avoid very large queries.

```
    /rdap/domains?name=abc*
```

Ordinarily, the wildcard must appear at the end of the string. But as a special
exception, the wildcard can be followed by .tld to indicate that only domains
from a specific TLD are desired. In this case, two characters are not required
before the wildcard.

```
    /rdap/domains?name=a*.tld
    /rdap/domains?name=*.tld
```

#### Search by nameserver name

Domain searches by nameserver name use the `nsLdhName` query parameter (LDH
means letters, digits, hyphen; Unicode nameserver names should be specified in
punycode form).

```
    /rdap/domains?nsLdhName=ns1.abc.tld
```

The query value can include a wildcard at the end of the string. There must be
at least two characters before the wildcard.

```
    /rdap/domains?nsLdhName=ns*
```

As a special exception, the wildcard can be followed by a domain name managed by
the Nomulus system to indicate that only nameservers subordinate to that domain
are desired. The two initial characters are not required in this case.

```
    /rdap/domains?nsLdhName=n*.localdomain.how
    /rdap/domains?nsLdhName=*.localdomain.how
```

#### Search by nameserver IP address

Domain searches by nameserver IP address use the `nsIp` query parameter.
Wildcards are not supported for IP address lookup.

```
    /rdap/domains?nsIp=1.2.3.4
```

### /rdap/entity/ <a id="entity"></a>

Look up a single entity by name. The entity ID is specified at the end of the
path. Two types of entities can be looked up: registrars (looked up by IANA
registrar ID) and contacts (lookup up by ROID). Registrar contacts are also
returned in results as entities, but cannot be looked up by themselves; they
only appear as part of information about a registrar.

```
    /rdap/entity/registrar-id
    /rdap/entity/ROID
```

### /rdap/entities? <a id="entities"></a>

Search for one or more entities (registrars or contacts). The RDAP specification
supports two kinds of searches: by full name or by handle. In either case,
requests not authenticated as associated with the registrar owning a contact
will not see personal data (name, address, email, phone, etc.) for the contact.
The visibility of registrar information, including registrar contacts, is
determined by the registrar's chosen WHOIS visibility settings.

#### Search by full name

Entity searches by full name use the `fn` query parameter. Results can include
contacts with a matching name, registrars with a matching registrar name, or
both. For contacts, the name used is the internationalized postal info name, if
present, or the localized postal info name otherwise.

```
    /rdap/entities?fn=Joseph%20Smith
    /rdap/entities?fn=tucows
```

A trailing wildcard is allowed, but at least two characters must precede the
wildcard.

```
    /rdap/entities?fn=Bobby%20Joe*
    /rdap/entities?fn=tu*
```

#### Search by handle

Entity searches by handle use the `handle` query parameter. Results can include
contacts with a matching ROID, registrars with a matching IANA registrar number,
or both.

```
    /rdap/entities?handle=12
    /rdap/entities?handle=ROID-1234
```

A trailing wildcard is allowed, with at least two character preceding it.
However, wildcard matching is only performed for contacts. Registrars will never
be returned for a wildcard entity search.

```
    /rdap/entities?handle=ROID-12*
```

### /rdap/help/ <a id="help"></a>

Retrieve help information. The desired help topic is specified at the end of the
path; if no topic is specified, the help index is returned.

```
    /rdap/help/
    /rdap/help/index
    /rdap/help/syntax
```

### /rdap/ip/ <a id="ip"></a>

IP addresses are not supported by registries, so queries of this type will
always return an error.

### /rdap/nameserver/ <a id="nameserver"></a>

Look up a single nameserver by fully qualified host name. The name is specified
at the end of the path.

```
    /rdap/nameserver/ns1.abc.tld
```

### /rdap/nameservers? <a id="nameservers"></a>

Search for one or more nameservers. The RDAP specification supports two kinds of
searches: by name or by IP address. The format for both is the same as for the
equivalent domain search by nameserver name and IP address, except that the
query parameters used are name and ip. See the domain search section above for
details.

```
    /rdap/nameservers?name=ns1.abc.tld
    /rdap/nameservers?name=ns*
    /rdap/nameservers?name=*.locallydefineddomain.how
    /rdap/nameservers?ip=1.2.3.4
```

## Experimental features <a id="experimental_features"></a>

As part of the RDAP Pilot Project, Nomulus incorporates a few extra features in
the RDAP endpoints.

### Authentication <a id="authentication"></a>

The RDAP RFCs do not include support for authentication or access controls. We
have implemented an experimental version that allows for authenticated access to
sensitive data such as contact names and addresses (a longtime concern with
WHOIS). We do this by leveraging the existing authentication/authorization
functionality of Nomulus' registrar console. Requests which can be authenticated
as coming from a specific registrar have access to all information about that
registrar's contact. Requests authenticated as coming from administrators of the
App Engine project have access to all contact information. In other cases, the
sensitive data will be hidden, and only the contact ROIDs and roles will be
displayed.

The registrar console uses the App Engine Users API to authenticate users. When
a request comes in, App Engine attempts to authenticate the user's email
address. If authentication is successful, the email address is then checked
against all registrar contacts in the system. If Nomulus is able to find a
matching registrar contact which has the `allow_console_access` permission, the
request is authorized for the associated registrar.

RDAP uses the same logic, but the registrar association is used only to
determine whether the request can see sensitive contact information (and deleted
items, as described in the section about the `includeDeleted` parameter).
Unauthenticated requests can still retrieve data, but that data will not be
visible.

To use RDAP in an authenticated fashion, first set up your email address for use
in the registrar console, as described elsewhere. Then check that you have
access to the console by loading the page:

```
    https://project-id.appspot.com/registrar
```

If you can see the registrar console, you are logged in correctly. Then change
the URL to an RDAP query on that same Nomulus instance, and you will be able to
see all data for your associated registrar.

### `registrar` parameter <a id="registrar_parameter"></a>

Ordinarily, all matching domains, hosts and contacts are included in the
returned result set. A request can specify that only items owned by a specific
registrar be included, by adding an extra parameter:

```
    /rdap/domains?name=*.tld&includeDeleted=true
```

This could be useful when designing a registrar user interface that uses RDAP as
a backend. Note that this parameter only excludes items; it does not cause items
to be shown that otherwise would not.

### `includeDeleted` parameter <a id="includedeleted_parameter"></a>

Ordinarily, deleted domains, hosts and contacts are not included in search
results. Authorized requests can specify that deleted items be included, by
adding an extra parameter:

```
    /rdap/domains?name=*.tld&includeDeleted=true
```

Deleted items are shown only if the request is authorized for the registrar that
owns the items, or if the request is authorized as an administrator. This
parameter can be combined with the registrar parameter.

### `formatOutput` parameter <a id="formatoutput_parameter"></a>

By default, the JSON responses contain no extra whitespace. A more readable
formatted version can be requested by adding an extra parameter:

```
    /rdap/domains?name=*.tld&formatOutput=true
```

The result is still valid JSON, but with extra whitespace added to align the
data on the page.

### `subtype` parameter <a id="subtype_parameter"></a>

The subtype parameter is used only for entity searches, to select whether the
results should include contacts, registrars or both. If specified, the subtype
should be 'all', 'contacts' or 'registrars'. Setting the subtype to 'all'
duplicates the normal behavior of returning both. Setting it to 'contacts' or
'registrars' causes an entity search to return only contacts or only registrars.

### Next page links <a id="next_page_links"></a>

The number of results returned in a domain, nameserver or entity search is
capped at a certain number. If there are more results than the limit, two
notices will be added to the returned JSON. The first is a standard Search
Policy notice, indicating that the results have been truncated. The second has a
title of `Navigation Links`, and a links section containing a link with `rel`
type `next`. This link can be used to retrieve the next page of results (which
may have a further link, and so on until all results have been retrieved).

The links are not foolproof, and caution should be exercised. If a matching
record is added or deleted between the time successive pages are requested, a
gap or a duplicate may appear.

Here are examples of the notices which will appear when the result set is
truncated.

```
  "notices" :
  [
    {
      "title" : "Search Policy",
      "type" : "result set truncated due to unexplainable reasons",
      "description" :
      [
        "Search results per query are limited."
      ]
    },
    {
      "title" : "Navigation Links",
      "links" :
      [
        {
          "type" : "application/rdap+json",
          "href" :
              "https://ex.com/rdap/domains?name=abc*.tld&cursor=a5927CDb902wE=",
          "rel" : "next"
        }
      ],
      "description" : [ "Links to related pages." ],
    },
    ...
```

### Additional features

We anticipate adding additional features during the pilot program, such as the
ability to page through search results. We will update the documentation when
these features are implemented.
