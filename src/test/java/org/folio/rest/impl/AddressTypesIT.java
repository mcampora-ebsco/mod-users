
package org.folio.rest.impl;

import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.folio.postgres.testing.PostgresTesterContainer;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.support.Address;
import org.folio.support.AddressType;
import org.folio.support.Personal;
import org.folio.support.User;
import org.folio.support.ValidationErrors;
import org.folio.support.VertxModule;
import org.folio.support.http.AddressTypesClient;
import org.folio.support.http.FakeTokenGenerator;
import org.folio.support.http.GroupsClient;
import org.folio.support.http.OkapiHeaders;
import org.folio.support.http.UsersClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.SneakyThrows;

@Timeout(value = 20, timeUnit = SECONDS)
@ExtendWith(VertxExtension.class)
class AddressTypesIT {
  private static UsersClient usersClient;
  private static GroupsClient groupsClient;
  private static AddressTypesClient addressTypesClient;

  @BeforeAll
  @SneakyThrows
  static void beforeAll(Vertx vertx, VertxTestContext context) {
    final var tenant = "users_integration_tests";
    final var token = new FakeTokenGenerator().generateToken();

    PostgresClient.setPostgresTester(new PostgresTesterContainer());

    final var port = NetworkUtils.nextFreePort();

    final var headers = new OkapiHeaders("http://localhost:" + port,
      tenant, token);

    usersClient = new UsersClient(new URI("http://localhost:" + port), headers);
    groupsClient = new GroupsClient(new URI("http://localhost:" + port), headers);
    addressTypesClient = new AddressTypesClient(
      new URI("http://localhost:" + port), headers);

    final var module = new VertxModule(vertx);

    module.deployModule(port)
      .onComplete(context.succeeding(res -> module.enableModule(headers,
          false, false)
        .onComplete(context.succeedingThenComplete())));
  }

  @BeforeEach
  public void beforeEach() {
    usersClient.deleteAllUsers();
    groupsClient.deleteAllGroups();
    addressTypesClient.deleteAllAddressTypes();
  }

  @Test
  void canCreateAnAddressType() {
    addressTypesClient.attemptToCreateAddressType(
        AddressType.builder()
          .addressType("Home")
          .build())
      .statusCode(is(HTTP_CREATED));
  }

  @Test
  void cannotCreateAnAddressTypeWithoutAName() {
    final var errors = addressTypesClient.attemptToCreateAddressType(
        AddressType.builder()
          .id(UUID.randomUUID().toString())
          .build())
      .statusCode(is(422))
      .extract().as(ValidationErrors.class);

    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getMessage(),
      is("must not be null"));
  }

  @Test
  void canDeleteAnAddressType() {
    final var home = addressTypesClient.createAddressType(
      AddressType.builder()
        .addressType("Home")
        .build());

    addressTypesClient.attemptToDeleteAddressType(home.getId())
      .statusCode(is(HTTP_NO_CONTENT));

    addressTypesClient.attemptToGetAddressType(home.getId())
      .statusCode(is(HTTP_NOT_FOUND));
  }

  @Test
  void canCreateUserWithMultipleAddresses() {
    final var homeAddressType = createAddressType("Home");
    final var returnsAddressType = createAddressType("Returns");

    final var userToCreate = User.builder()
      .username("juliab")
      .personal(Personal.builder()
        .lastName("brockhurst")
        .addresses(List.of(
          Address.builder().addressTypeId(homeAddressType.getId()).build(),
          Address.builder().addressTypeId(returnsAddressType.getId()).build()))
        .build())
      .build();

    final var createdUser = usersClient.createUser(userToCreate);

    assertThat(createdUser.getPersonal().getAddresses().size(), is(2));
  }

  @Test
  void cannotCreateUserWithAddressesOfUnknownType() {
    final var userWithMultipleAddresses = User.builder()
      .username("julia")
      .personal(Personal.builder()
        .lastName("brockhurst")
        .addresses(List.of(
          Address.builder().addressTypeId(UUID.randomUUID().toString()).build()))
        .build())
      .build();

    usersClient.attemptToCreateUser(userWithMultipleAddresses)
      .statusCode(400)
      .body(is("You cannot add addresses with non-existent address types"));
  }

  @Test
  void cannotCreateUserWithMultipleAddressesOfSameType() {
    final var paymentAddressType = createAddressType("Payment");

    final var userWithMultipleAddresses = User.builder()
      .username("julia")
      .personal(Personal.builder()
        .lastName("brockhurst")
        .addresses(List.of(
          Address.builder().addressTypeId(paymentAddressType.getId()).build(),
          Address.builder().addressTypeId(paymentAddressType.getId()).build()))
        .build())
      .build();

    usersClient.attemptToCreateUser(userWithMultipleAddresses)
      .statusCode(400)
      .body(is("Users are limited to one address per addresstype"));
  }

  @Test
  void cannotUpdateUserWithMultipleAddressesOfSameType() {
    final var paymentAddressType = createAddressType("Payment");

    final var user = usersClient.createUser(User.builder()
      .username("julia")
      .build());

    final var userWithMultipleAddresses = User.builder()
      .id(user.getId())
      .username("julia")
      .personal(Personal.builder()
        .lastName("brockhurst")
        .addresses(List.of(
          Address.builder().addressTypeId(paymentAddressType.getId()).build(),
          Address.builder().addressTypeId(paymentAddressType.getId()).build()))
        .build())
      .build();

    usersClient.attemptToUpdateUser(userWithMultipleAddresses)
      .statusCode(400)
      .body(is("Users are limited to one address per addresstype"));
  }

  private AddressType createAddressType(String addressTypeId) {
    return addressTypesClient.createAddressType(
      AddressType.builder()
        .addressType(addressTypeId)
        .build());
  }
}
