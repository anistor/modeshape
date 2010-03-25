/*
 * ModeShape (http://www.modeshape.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of 
 * individual contributors.
 *
 * ModeShape is free software. Unless otherwise indicated, all code in ModeShape
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * 
 * ModeShape is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.jcr;

import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.jcr.RepositoryException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.modeshape.graph.ExecutionContext;
import org.modeshape.graph.Graph;
import org.modeshape.graph.MockSecurityContext;
import org.modeshape.graph.SecurityContext;
import org.modeshape.graph.connector.RepositoryConnection;
import org.modeshape.graph.connector.RepositoryConnectionFactory;
import org.modeshape.graph.connector.RepositorySourceException;
import org.modeshape.graph.connector.inmemory.InMemoryRepositorySource;
import org.modeshape.graph.observe.MockObservable;
import org.modeshape.graph.property.NamespaceRegistry;
import org.modeshape.graph.property.Path;
import org.modeshape.graph.property.PathFactory;
import org.modeshape.graph.query.parse.QueryParsers;
import org.modeshape.jcr.nodetype.NodeTypeTemplate;
import org.modeshape.jcr.xpath.XPathQueryParser;

/**
 * Abstract test class that sets up a working JcrSession environment, albeit with a mocked JcrRepository.
 * 
 * @see AbstractJcrTest for an alternative with a less complete environment
 */
public abstract class AbstractSessionTest {

    protected String workspaceName;
    protected ExecutionContext context;
    protected InMemoryRepositorySource source;
    protected JcrWorkspace workspace;
    protected JcrSession session;
    protected Graph graph;
    protected RepositoryConnectionFactory connectionFactory;
    protected RepositoryNodeTypeManager repoTypeManager;
    protected Map<String, Object> sessionAttributes;
    protected Map<JcrRepository.Option, String> options;
    protected NamespaceRegistry registry;
    protected WorkspaceLockManager workspaceLockManager;
    protected QueryParsers parsers;
    @Mock
    protected JcrRepository repository;

    protected void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);

        workspaceName = "workspace1";
        final String repositorySourceName = "repository";

        // Set up the source ...
        source = new InMemoryRepositorySource();
        source.setName(workspaceName);
        source.setDefaultWorkspaceName(workspaceName);

        // Set up the execution context ...
        context = new ExecutionContext();
        // Register the test namespace
        context.getNamespaceRegistry().register(TestLexicon.Namespace.PREFIX, TestLexicon.Namespace.URI);
        PathFactory pathFactory = context.getValueFactories().getPathFactory();

        // Set up the initial content ...
        graph = Graph.create(source, context);

        // Make sure the path to the namespaces exists ...
        graph.create("/jcr:system").and(); // .and().create("/jcr:system/mode:namespaces");
        graph.set("jcr:primaryType").on("/jcr:system").to(ModeShapeLexicon.SYSTEM);

        graph.create("/jcr:system/mode:namespaces").and();
        graph.set("jcr:primaryType").on("/jcr:system/mode:namespaces").to(ModeShapeLexicon.NAMESPACES);

        // Add the built-ins, ensuring we overwrite any badly-initialized values ...
        for (Map.Entry<String, String> builtIn : JcrNamespaceRegistry.STANDARD_BUILT_IN_NAMESPACES_BY_PREFIX.entrySet()) {
            context.getNamespaceRegistry().register(builtIn.getKey(), builtIn.getValue());
        }

        initializeContent();

        // Stub out the connection factory ...
        connectionFactory = new RepositoryConnectionFactory() {
            /**
             * {@inheritDoc}
             * 
             * @see org.modeshape.graph.connector.RepositoryConnectionFactory#createConnection(java.lang.String)
             */
            public RepositoryConnection createConnection( String sourceName ) throws RepositorySourceException {
                return repositorySourceName.equals(sourceName) ? source.getConnection() : null;
            }
        };

        when(repository.getExecutionContext()).thenReturn(context);
        when(repository.getRepositorySourceName()).thenReturn(repositorySourceName);
        when(repository.getPersistentRegistry()).thenReturn(context.getNamespaceRegistry());
        when(repository.createWorkspaceGraph(anyString(), (ExecutionContext)anyObject())).thenAnswer(new Answer<Graph>() {
            public Graph answer( InvocationOnMock invocation ) throws Throwable {
                return graph;
            }
        });
        when(repository.createSystemGraph(context)).thenAnswer(new Answer<Graph>() {
            public Graph answer( InvocationOnMock invocation ) throws Throwable {
                return graph;
            }
        });
        when(this.repository.getRepositoryObservable()).thenReturn(new MockObservable());

        // Stub out the repository, since we only need a few methods ...
        repoTypeManager = new RepositoryNodeTypeManager(repository, true);
        when(repository.getRepositoryTypeManager()).thenReturn(repoTypeManager);

        try {
            this.repoTypeManager.registerNodeTypes(new CndNodeTypeSource(new String[] {"/org/modeshape/jcr/jsr_170_builtins.cnd",
                "/org/modeshape/jcr/dna_builtins.cnd"}));
            this.repoTypeManager.registerNodeTypes(new NodeTemplateNodeTypeSource(getTestTypes()));

        } catch (RepositoryException re) {
            re.printStackTrace();
            throw new IllegalStateException("Could not load node type definition files", re);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            throw new IllegalStateException("Could not access node type definition files", ioe);
        }
        this.repoTypeManager.projectOnto(graph, pathFactory.create("/jcr:system/jcr:nodeTypes"));

        Path locksPath = pathFactory.createAbsolutePath(JcrLexicon.SYSTEM, ModeShapeLexicon.LOCKS);
        workspaceLockManager = new WorkspaceLockManager(context, repository, workspaceName, locksPath);

        when(repository.getLockManager(anyString())).thenAnswer(new Answer<WorkspaceLockManager>() {
            public WorkspaceLockManager answer( InvocationOnMock invocation ) throws Throwable {
                return workspaceLockManager;
            }
        });

        initializeOptions();
        when(repository.getOptions()).thenReturn(options);

        // Set up the parsers for the repository (we only need the XPath parsers at the moment) ...
        parsers = new QueryParsers(new XPathQueryParser());
        when(repository.queryParsers()).thenReturn(parsers);

        // Set up the session attributes ...
        // Set up the session attributes ...
        sessionAttributes = new HashMap<String, Object>();
        sessionAttributes.put("attribute1", "value1");

        // Now create the workspace ...
        SecurityContext mockSecurityContext = new MockSecurityContext(null, Collections.singleton(ModeShapeRoles.READWRITE));
        workspace = new JcrWorkspace(repository, workspaceName, context.with(mockSecurityContext), sessionAttributes);

        // Create the session and log in ...
        session = (JcrSession)workspace.getSession();
        registry = session.getExecutionContext().getNamespaceRegistry();
    }

    protected List<NodeTypeTemplate> getTestTypes() {
        return Collections.emptyList();
    }

    protected void initializeContent() {

    }

    protected void initializeOptions() {
        // Stub out the repository options ...
        options = new EnumMap<JcrRepository.Option, String>(JcrRepository.Option.class);
        options.put(JcrRepository.Option.PROJECT_NODE_TYPES, Boolean.FALSE.toString());

    }
}