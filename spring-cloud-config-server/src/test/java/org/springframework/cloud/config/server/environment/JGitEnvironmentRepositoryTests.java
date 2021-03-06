/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.config.server.environment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.util.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.test.ConfigServerTestUtils;
import org.springframework.core.env.StandardEnvironment;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

/**
 * @author Dave Syer
 *
 */
public class JGitEnvironmentRepositoryTests {

	private StandardEnvironment environment = new StandardEnvironment();
	private JGitEnvironmentRepository repository = new JGitEnvironmentRepository(
			this.environment);

	private File basedir = new File("target/config");

	@Before
	public void init() throws Exception {
		String uri = ConfigServerTestUtils.prepareLocalRepo();
		this.repository.setUri(uri);
		if (this.basedir.exists()) {
			FileUtils.delete(this.basedir, FileUtils.RECURSIVE | FileUtils.RETRY);
		}
	}

	@Test
	public void vanilla() {
		this.repository.findOne("bar", "staging", "master");
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/bar.properties",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void nested() throws IOException {
		String uri = ConfigServerTestUtils.prepareLocalRepo("another-config-repo");
		this.repository.setUri(uri);
		this.repository.setSearchPaths(new String[] {"sub"});
		this.repository.findOne("bar", "staging", "master");
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/sub/application.yml",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void placeholderInSearchPath() throws IOException {
		String uri = ConfigServerTestUtils.prepareLocalRepo("another-config-repo");
		this.repository.setUri(uri);
		this.repository.setSearchPaths(new String[] {"{application}"});
		this.repository.findOne("sub", "staging", "master");
		Environment environment = this.repository.findOne("sub", "staging", "master");
		assertEquals(1, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/sub/application.yml",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	private void assertVersion(Environment environment) {
		String version = environment.getVersion();
		assertNotNull("version was null", version);
		assertTrue("version length was wrong", version.length() >= 40 && version.length() <= 64);
	}

	@Test
	public void nestedPattern() throws IOException {
		String uri = ConfigServerTestUtils.prepareLocalRepo("another-config-repo");
		this.repository.setUri(uri);
		this.repository.setSearchPaths(new String[] {"sub*"});
		this.repository.findOne("bar", "staging", "master");
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/sub/application.yml",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void branch() {
		this.repository.setBasedir(this.basedir);
		Environment environment = this.repository.findOne("bar", "staging", "raw");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/bar.properties",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void tag() {
		this.repository.setBasedir(this.basedir);
		Environment environment = this.repository.findOne("bar", "staging", "foo");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/bar.properties", environment
				.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void basedir() {
		this.repository.setBasedir(this.basedir);
		this.repository.findOne("bar", "staging", "master");
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/bar.properties",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void basedirExists() throws Exception {
		assertTrue(this.basedir.mkdirs());
		assertTrue(new File(this.basedir, ".nothing").createNewFile());
		this.repository.setBasedir(this.basedir);
		this.repository.findOne("bar", "staging", "master");
		Environment environment = this.repository.findOne("bar", "staging", "master");
		assertEquals(2, environment.getPropertySources().size());
		assertEquals(this.repository.getUri() + "/bar.properties",
				environment.getPropertySources().get(0).getName());
		assertVersion(environment);
	}

	@Test
	public void uriWithHostOnly() throws Exception {
		this.repository.setUri("git://localhost");
		assertEquals("git://localhost/", this.repository.getUri());
	}

	@Test
	public void uriWithHostAndPath() throws Exception {
		this.repository.setUri("git://localhost/foo/");
		assertEquals("git://localhost/foo", this.repository.getUri());
	}

	@Test
	public void afterPropertiesSet_CloneOnStartTrue_CloneAndFetchCalled()
			throws Exception {
		Git mockGit = mock(Git.class);
		CloneCommand mockCloneCommand = mock(CloneCommand.class);

		when(mockCloneCommand.setURI(anyString())).thenReturn(mockCloneCommand);
		when(mockCloneCommand.setDirectory(any(File.class))).thenReturn(mockCloneCommand);

		JGitEnvironmentRepository envRepository = new JGitEnvironmentRepository(
				this.environment);
		envRepository.setGitFactory(new MockGitFactory(mockGit, mockCloneCommand));
		envRepository.setUri("http://somegitserver/somegitrepo");
		envRepository.setCloneOnStart(true);
		envRepository.afterPropertiesSet();
		verify(mockCloneCommand, times(1)).call();
	}

	@Test
	public void afterPropertiesSet_CloneOnStartFalse_CloneAndFetchNotCalled()
			throws Exception {
		Git mockGit = mock(Git.class);
		CloneCommand mockCloneCommand = mock(CloneCommand.class);

		when(mockCloneCommand.setURI(anyString())).thenReturn(mockCloneCommand);
		when(mockCloneCommand.setDirectory(any(File.class))).thenReturn(mockCloneCommand);

		JGitEnvironmentRepository envRepository = new JGitEnvironmentRepository(
				this.environment);
		envRepository.setGitFactory(new MockGitFactory(mockGit, mockCloneCommand));
		envRepository.setUri("http://somegitserver/somegitrepo");
		envRepository.afterPropertiesSet();
		verify(mockCloneCommand, times(0)).call();
		verify(mockGit, times(0)).fetch();
	}

	@Test
	public void afterPropertiesSet_CloneOnStartTrueWithFileURL_CloneAndFetchNotCalled()
			throws Exception {
		Git mockGit = mock(Git.class);
		CloneCommand mockCloneCommand = mock(CloneCommand.class);

		when(mockCloneCommand.setURI(anyString())).thenReturn(mockCloneCommand);
		when(mockCloneCommand.setDirectory(any(File.class))).thenReturn(mockCloneCommand);

		JGitEnvironmentRepository envRepository = new JGitEnvironmentRepository(
				this.environment);
		envRepository.setGitFactory(new MockGitFactory(mockGit, mockCloneCommand));
		envRepository.setUri("file://somefilesystem/somegitrepo");
		envRepository.setCloneOnStart(true);
		envRepository.afterPropertiesSet();
		verify(mockCloneCommand, times(0)).call();
		verify(mockGit, times(0)).fetch();
	}

	@Test
	public void shouldPullForcepullNotClean() throws Exception {
		Git git = mock(Git.class);
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		Repository repository = mock(Repository.class);
		StoredConfig storedConfig = mock(StoredConfig.class);

		when(git.status()).thenReturn(statusCommand);
		when(git.getRepository()).thenReturn(repository);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(false);

		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);
		repo.setForcePull(true);

		boolean shouldPull = repo.shouldPull(git);

		assertThat("shouldPull was false", shouldPull, is(true));
	}

	@Test
	public void shouldPullNotClean() throws Exception {
		Git git = mock(Git.class);
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		Repository repository = mock(Repository.class);
		StoredConfig storedConfig = mock(StoredConfig.class);

		when(git.status()).thenReturn(statusCommand);
		when(git.getRepository()).thenReturn(repository);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(false);

		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);

		boolean shouldPull = repo.shouldPull(git);

		assertThat("shouldPull was true", shouldPull, is(false));
	}

	@Test
	public void shouldPullClean() throws Exception {
		Git git = mock(Git.class);
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		Repository repository = mock(Repository.class);
		StoredConfig storedConfig = mock(StoredConfig.class);

		when(git.status()).thenReturn(statusCommand);
		when(git.getRepository()).thenReturn(repository);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(true);

		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);

		boolean shouldPull = repo.shouldPull(git);

		assertThat("shouldPull was false", shouldPull, is(true));
	}

	@Test
	public void testFetchException() throws Exception {

		Git git = mock(Git.class);
		CloneCommand cloneCommand = mock(CloneCommand.class);
		MockGitFactory factory = new MockGitFactory(git, cloneCommand);
		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);
		this.repository.setGitFactory(factory);

		//refresh()->shouldPull
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		when(git.status()).thenReturn(statusCommand);
		Repository repository = mock(Repository.class);
		when(git.getRepository()).thenReturn(repository);
		StoredConfig storedConfig = mock(StoredConfig.class);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(true);

		//refresh()->fetch
		FetchCommand fetchCommand = mock(FetchCommand.class);
		when(git.fetch()).thenReturn(fetchCommand);
		when(fetchCommand.setRemote(anyString())).thenReturn(fetchCommand);
		when(fetchCommand.call()).thenThrow(new InvalidRemoteException("invalid mock remote")); //here is our exception we are testing

		//refresh()->checkout
		CheckoutCommand checkoutCommand = mock(CheckoutCommand.class);
		//refresh()->checkout->containsBranch
		ListBranchCommand listBranchCommand = mock(ListBranchCommand.class);
		when(git.checkout()).thenReturn(checkoutCommand);
		when(git.branchList()).thenReturn(listBranchCommand);
		List<Ref> refs = new ArrayList<>();
		Ref ref = mock(Ref.class);
		refs.add(ref);
		when(ref.getName()).thenReturn("/master");
		when(listBranchCommand.call()).thenReturn(refs);

		//refresh()->merge
		MergeCommand mergeCommand = mock(MergeCommand.class);
		when(git.merge()).thenReturn(mergeCommand);
		when(mergeCommand.call()).thenThrow(new NotMergedException()); //here is our exception we are testing

		//refresh()->return git.getRepository().getRef("HEAD").getObjectId().getName();
		Ref headRef = mock(Ref.class);
		when(repository.getRef(anyString())).thenReturn(headRef);

		ObjectId newObjectId = ObjectId.fromRaw(new int[]{1,2,3,4,5});
		when(headRef.getObjectId()).thenReturn(newObjectId);

		SearchPathLocator.Locations locations = this.repository.getLocations("bar", "staging", null);
		assertEquals(locations.getVersion(),newObjectId.getName());
	}


	@Test
    public void testMergeException() throws Exception {

		Git git = mock(Git.class);
		CloneCommand cloneCommand = mock(CloneCommand.class);
		MockGitFactory factory = new MockGitFactory(git, cloneCommand);
		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);
		this.repository.setGitFactory(factory);

		//refresh()->shouldPull
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		when(git.status()).thenReturn(statusCommand);
		Repository repository = mock(Repository.class);
		when(git.getRepository()).thenReturn(repository);
		StoredConfig storedConfig = mock(StoredConfig.class);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(true);

		//refresh()->fetch
		FetchCommand fetchCommand = mock(FetchCommand.class);
		FetchResult fetchResult = mock(FetchResult.class);
		when(git.fetch()).thenReturn(fetchCommand);
		when(fetchCommand.setRemote(anyString())).thenReturn(fetchCommand);
		when(fetchCommand.call()).thenReturn(fetchResult);
		when(fetchResult.getTrackingRefUpdates()).thenReturn(Collections.EMPTY_LIST);

		//refresh()->checkout
		CheckoutCommand checkoutCommand = mock(CheckoutCommand.class);
		//refresh()->checkout->containsBranch
		ListBranchCommand listBranchCommand = mock(ListBranchCommand.class);
		when(git.checkout()).thenReturn(checkoutCommand);
		when(git.branchList()).thenReturn(listBranchCommand);
		List<Ref> refs = new ArrayList<>();
		Ref ref = mock(Ref.class);
		refs.add(ref);
		when(ref.getName()).thenReturn("/master");
		when(listBranchCommand.call()).thenReturn(refs);

		//refresh()->merge
		MergeCommand mergeCommand = mock(MergeCommand.class);
		when(git.merge()).thenReturn(mergeCommand);
		when(mergeCommand.call()).thenThrow(new NotMergedException()); //here is our exception we are testing

		//refresh()->return git.getRepository().getRef("HEAD").getObjectId().getName();
		Ref headRef = mock(Ref.class);
		when(repository.getRef(anyString())).thenReturn(headRef);

		ObjectId newObjectId = ObjectId.fromRaw(new int[]{1,2,3,4,5});
		when(headRef.getObjectId()).thenReturn(newObjectId);

		SearchPathLocator.Locations locations = this.repository.getLocations("bar", "staging", "master");
		assertEquals(locations.getVersion(),newObjectId.getName());
    }

	@Test
	public void testResetHardException() throws Exception {

		Git git = mock(Git.class);
		CloneCommand cloneCommand = mock(CloneCommand.class);
		MockGitFactory factory = new MockGitFactory(git, cloneCommand);
		JGitEnvironmentRepository repo = new JGitEnvironmentRepository(
				this.environment);
		this.repository.setGitFactory(factory);

		//refresh()->shouldPull
		StatusCommand statusCommand = mock(StatusCommand.class);
		Status status = mock(Status.class);
		when(git.status()).thenReturn(statusCommand);
		Repository repository = mock(Repository.class);
		when(git.getRepository()).thenReturn(repository);
		StoredConfig storedConfig = mock(StoredConfig.class);
		when(repository.getConfig()).thenReturn(storedConfig);
		when(storedConfig.getString("remote", "origin", "url")).thenReturn("http://example/git");
		when(statusCommand.call()).thenReturn(status);
		when(status.isClean()).thenReturn(true).thenReturn(false);

		//refresh()->fetch
		FetchCommand fetchCommand = mock(FetchCommand.class);
		FetchResult fetchResult = mock(FetchResult.class);
		when(git.fetch()).thenReturn(fetchCommand);
		when(fetchCommand.setRemote(anyString())).thenReturn(fetchCommand);
		when(fetchCommand.call()).thenReturn(fetchResult);
		when(fetchResult.getTrackingRefUpdates()).thenReturn(Collections.EMPTY_LIST);

		//refresh()->checkout
		CheckoutCommand checkoutCommand = mock(CheckoutCommand.class);
		//refresh()->checkout->containsBranch
		ListBranchCommand listBranchCommand = mock(ListBranchCommand.class);
		when(git.checkout()).thenReturn(checkoutCommand);
		when(git.branchList()).thenReturn(listBranchCommand);
		List<Ref> refs = new ArrayList<>();
		Ref ref = mock(Ref.class);
		refs.add(ref);
		when(ref.getName()).thenReturn("/master");
		when(listBranchCommand.call()).thenReturn(refs);

		//refresh()->merge
		MergeCommand mergeCommand = mock(MergeCommand.class);
		when(git.merge()).thenReturn(mergeCommand);
		when(mergeCommand.call()).thenThrow(new NotMergedException()); //here is our exception we are testing

		//refresh()->hardReset
		ResetCommand resetCommand = mock(ResetCommand.class);
		when(git.reset()).thenReturn(resetCommand);
		when(resetCommand.call()).thenReturn(ref);

		//refresh()->return git.getRepository().getRef("HEAD").getObjectId().getName();
		Ref headRef = mock(Ref.class);
		when(repository.getRef(anyString())).thenReturn(headRef);

		ObjectId newObjectId = ObjectId.fromRaw(new int[]{1,2,3,4,5});
		when(headRef.getObjectId()).thenReturn(newObjectId);

		SearchPathLocator.Locations locations = this.repository.getLocations("bar", "staging", "master");
		assertEquals(locations.getVersion(),newObjectId.getName());
	}
	
	@Test
	public void shouldDeleteBaseDirWhenCloneFails()	throws Exception {
		Git mockGit = mock(Git.class);
		CloneCommand mockCloneCommand = mock(CloneCommand.class);

		when(mockCloneCommand.setURI(anyString())).thenReturn(mockCloneCommand);
		when(mockCloneCommand.setDirectory(any(File.class))).thenReturn(mockCloneCommand);
		when(mockCloneCommand.call()).thenThrow(new TransportException("failed to clone"));

		JGitEnvironmentRepository envRepository = new JGitEnvironmentRepository(
				this.environment);
		envRepository.setGitFactory(new MockGitFactory(mockGit, mockCloneCommand));
		envRepository.setUri("http://somegitserver/somegitrepo");
		envRepository.setBasedir(this.basedir);
		
		try {
			envRepository.findOne("bar", "staging", "master");
		} 
		catch (Exception ex) {
			// expected - ignore
		}
		
		assertFalse("baseDir should be deleted when clone fails", this.basedir.exists());
	}

	class MockGitFactory extends JGitEnvironmentRepository.JGitFactory {

		private Git mockGit;
		private CloneCommand mockCloneCommand;

		public MockGitFactory (Git mockGit, CloneCommand mockCloneCommand) {
			this.mockGit = mockGit;
			this.mockCloneCommand = mockCloneCommand;
		}

		@Override
		public Git getGitByOpen(File file) throws IOException {
			return this.mockGit;
		}

		@Override
		public CloneCommand getCloneCommandByCloneRepository() {
			return this.mockCloneCommand;
		}
	}
}
