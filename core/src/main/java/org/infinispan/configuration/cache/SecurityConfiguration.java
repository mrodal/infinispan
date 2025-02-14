package org.infinispan.configuration.cache;

import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.commons.configuration.attributes.Matchable;

/**
 * SecurityConfiguration.
 *
 * @author Tristan Tarrant
 * @since 7.0
 */
public class SecurityConfiguration implements Matchable<SecurityConfiguration> {

   private final AuthorizationConfiguration authorization;

   SecurityConfiguration(AuthorizationConfiguration authorization) {
      this.authorization = authorization;
   }

   public AuthorizationConfiguration authorization() {
      return authorization;
   }

   @Override
   public String toString() {
      return "[authorization=" + authorization + ']';
   }

   @Override
   public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((authorization == null) ? 0 : authorization.hashCode());
      return result;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj)
         return true;
      if (obj == null)
         return false;
      if (getClass() != obj.getClass())
         return false;
      SecurityConfiguration other = (SecurityConfiguration) obj;
      if (authorization == null) {
         if (other.authorization != null)
            return false;
      } else {
         return authorization.equals(other.authorization);
      }
      return true;
   }

   public AttributeSet attributes() {
      return null;
   }
}
