package org.triplea.modules.game.listing;

import org.junit.jupiter.api.Test;
import org.triplea.http.client.lobby.game.lobby.watcher.GameListingClient;
import org.triplea.modules.http.AllowedUserRole;
import org.triplea.modules.http.ProtectedEndpointTest;

class GameListingControllerTest extends ProtectedEndpointTest<GameListingClient> {

  GameListingControllerTest() {
    super(AllowedUserRole.HOST, GameListingClient::newClient);
  }

  @Test
  void fetchGames() {
    verifyEndpoint(AllowedUserRole.ANONYMOUS, GameListingClient::fetchGameListing);
  }

  @Test
  void bootGame() {
    verifyEndpoint(AllowedUserRole.MODERATOR, client -> client.bootGame("game-id"));
  }
}
